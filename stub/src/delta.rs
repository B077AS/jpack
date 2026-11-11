//! Updating one big file by fetching only what changed in it (used for AppImages).
//!
//! The publisher ships, next to the new file, a small **block index**: a checksum for every block of the new file. The
//! stub scans the old file on disk for blocks it already has, at any position (the checksum "rolls" one byte at a time, so
//! blocks that moved are found too), and builds the new file from those plus the blocks it fetches with HTTP range requests.
//! The whole result is checked against the hash in the manifest before it replaces anything.
//!
//! Index format (all numbers little-endian):
//! ```text
//! "JPBX" | version u8 = 1 | 3 bytes 0 | block size u32 | file size u64 | block count u32 | per block: weak u32, strong [u8; 16]
//! ```
//! `weak` is the rsync checksum of the block (`a` = sum of the bytes, `b` = sum of byte * distance to the end, both mod 2^16,
//! `a | b << 16`); `strong` is the first 16 bytes of the block's SHA-256. The last block is padded with zeros to a full block
//! for its checksums; it is never matched against the old file, only fetched.

use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom, Write};

const MAGIC: &[u8; 4] = b"JPBX";
const HEADER: usize = 24;
const ENTRY: usize = 20;
/// Blocks to fetch in one request at most (more adjacent missing blocks are fetched with a second request).
const MAX_RUN: u64 = 8 * 1024 * 1024;
/// How much of the old file is read at a time while scanning it.
const CHUNK: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BlockSum {
    pub weak: u32,
    pub strong: [u8; 16],
}

#[derive(Debug, PartialEq, Eq)]
pub struct Index {
    pub block_size: usize,
    pub file_size: u64,
    pub blocks: Vec<BlockSum>,
}

impl Index {
    pub fn parse(bytes: &[u8]) -> Result<Index, String> {
        if bytes.len() < HEADER || &bytes[0..4] != MAGIC || bytes[4] != 1 {
            return Err("the block index is not in a format this version understands".into());
        }
        let block_size = u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
        let file_size = u64::from_le_bytes(bytes[12..20].try_into().unwrap());
        let count = u32::from_le_bytes(bytes[20..24].try_into().unwrap()) as usize;
        if !(512..=(1 << 22)).contains(&block_size) {
            return Err("the block index has an unusable block size".into());
        }
        if file_size.div_ceil(block_size as u64) != count as u64 || bytes.len() != HEADER + ENTRY * count {
            return Err("the block index is damaged".into());
        }
        let blocks = bytes[HEADER..]
            .chunks_exact(ENTRY)
            .map(|entry| BlockSum { weak: u32::from_le_bytes(entry[0..4].try_into().unwrap()), strong: entry[4..20].try_into().unwrap() })
            .collect();
        Ok(Index { block_size, file_size, blocks })
    }

    /// Where block `i` of the file lies: `start..end` (the last block is shorter when the size is no multiple).
    pub fn range(&self, i: usize) -> (u64, u64) {
        let start = i as u64 * self.block_size as u64;
        (start, (start + self.block_size as u64).min(self.file_size))
    }

    /// Bytes that have to be fetched for the blocks not found in the old file.
    pub fn missing_bytes(&self, local: &[Option<u64>]) -> u64 {
        (0..self.blocks.len()).filter(|&i| local[i].is_none()).map(|i| self.range(i).1 - self.range(i).0).sum()
    }

    #[cfg(test)]
    pub fn build(data: &[u8], block_size: usize) -> Index {
        let blocks = data
            .chunks(block_size)
            .map(|chunk| {
                let mut padded = chunk.to_vec();
                padded.resize(block_size, 0);
                BlockSum { weak: weak_sum(&padded), strong: strong_sum(&padded) }
            })
            .collect();
        Index { block_size, file_size: data.len() as u64, blocks }
    }

    #[cfg(test)]
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&[1, 0, 0, 0]);
        out.extend_from_slice(&(self.block_size as u32).to_le_bytes());
        out.extend_from_slice(&self.file_size.to_le_bytes());
        out.extend_from_slice(&(self.blocks.len() as u32).to_le_bytes());
        for block in &self.blocks {
            out.extend_from_slice(&block.weak.to_le_bytes());
            out.extend_from_slice(&block.strong);
        }
        out
    }
}

/// The rsync checksum of a block.
pub fn weak_sum(block: &[u8]) -> u32 {
    let (a, b) = sums(block);
    a | (b << 16)
}

fn sums(block: &[u8]) -> (u32, u32) {
    let length = block.len() as u32;
    let (mut a, mut b) = (0u32, 0u32);
    for (i, &x) in block.iter().enumerate() {
        a = a.wrapping_add(x as u32);
        b = b.wrapping_add((length - i as u32).wrapping_mul(x as u32));
    }
    (a & 0xFFFF, b & 0xFFFF)
}

pub fn strong_sum(block: &[u8]) -> [u8; 16] {
    Sha256::digest(block)[..16].try_into().unwrap()
}

/// Looks in `old` for the blocks of the new file. The result has, per block of the new file, the offset in the old file
/// where the same bytes were found.
pub fn find_local(old: &mut File, index: &Index, progress: &mut dyn FnMut(u64)) -> io::Result<Vec<Option<u64>>> {
    let bs = index.block_size;
    let mut found: Vec<Option<u64>> = vec![None; index.blocks.len()];
    // Only complete blocks can match: the last, partial one is always fetched.
    let eligible = if index.file_size % bs as u64 == 0 { index.blocks.len() } else { index.blocks.len() - 1 };
    let length = old.metadata()?.len();
    if eligible == 0 || length < bs as u64 {
        return Ok(found);
    }
    let mut by_weak: HashMap<u32, Vec<usize>> = HashMap::new();
    let mut filter = vec![false; 1 << 20]; // most positions match nothing: rule them out without a map lookup
    for (i, block) in index.blocks.iter().enumerate().take(eligible) {
        by_weak.entry(block.weak).or_default().push(i);
        filter[(block.weak & 0xFFFFF) as usize] = true;
    }

    old.seek(SeekFrom::Start(0))?;
    let mut data: Vec<u8> = Vec::with_capacity(CHUNK + bs);
    let mut base: u64 = 0; // file offset of data[0]
    let mut pos: u64 = 0; // start of the window being looked at
    let mut rolling: Option<(u32, u32)> = None;
    let mut reached_end = false;
    loop {
        let mut off = (pos - base) as usize;
        if data.len() < off + bs + 1 && !reached_end {
            data.drain(..off);
            base = pos;
            off = 0;
            let keep = data.len();
            data.resize(keep + CHUNK, 0);
            let mut filled = 0;
            while filled < CHUNK {
                let n = old.read(&mut data[keep + filled..])?;
                if n == 0 {
                    reached_end = true;
                    break;
                }
                filled += n;
            }
            data.truncate(keep + filled);
            progress(base);
        }
        if off + bs > data.len() {
            break;
        }
        let window = &data[off..off + bs];
        let (a, b) = rolling.unwrap_or_else(|| sums(window));
        let weak = a | (b << 16);
        if filter[(weak & 0xFFFFF) as usize] {
            if let Some(candidates) = by_weak.get(&weak) {
                let strong = strong_sum(window);
                let mut matched = false;
                for &i in candidates {
                    if index.blocks[i].strong == strong {
                        found[i].get_or_insert(pos);
                        matched = true;
                    }
                }
                if matched {
                    // The next block of the new file very likely follows this one in the old file as well.
                    pos += bs as u64;
                    rolling = None;
                    continue;
                }
            }
        }
        if off + bs >= data.len() {
            break;
        }
        let (out, incoming) = (data[off] as u32, data[off + bs] as u32);
        let a2 = a.wrapping_sub(out).wrapping_add(incoming) & 0xFFFF;
        let b2 = b.wrapping_sub((bs as u32).wrapping_mul(out)).wrapping_add(a2) & 0xFFFF;
        rolling = Some((a2, b2));
        pos += 1;
    }
    Ok(found)
}

/// Writes the new file: blocks found in `old` are copied, the others come from `fetch(start, end)` (the bytes
/// `start..end` of the new file). Returns the SHA-256 of what was written, as hex.
pub fn assemble(
    old: &mut File,
    local: &[Option<u64>],
    index: &Index,
    out: &mut dyn Write,
    fetch: &mut dyn FnMut(u64, u64) -> Result<Vec<u8>, String>,
    fetched: &mut dyn FnMut(u64),
) -> Result<String, String> {
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; index.block_size];
    let mut i = 0;
    while i < index.blocks.len() {
        if let Some(offset) = local[i] {
            old.seek(SeekFrom::Start(offset)).map_err(|e| format!("cannot read the old file: {e}"))?;
            old.read_exact(&mut buffer).map_err(|e| format!("cannot read the old file: {e}"))?;
            hasher.update(&buffer);
            out.write_all(&buffer).map_err(|e| format!("cannot write the new file: {e}"))?;
            i += 1;
            continue;
        }
        // A run of blocks that have to be fetched, as one request.
        let start = index.range(i).0;
        let mut j = i;
        while j < index.blocks.len() && local[j].is_none() && index.range(j).1 - start <= MAX_RUN {
            j += 1;
        }
        let end = index.range(j - 1).1;
        let bytes = fetch(start, end)?;
        if bytes.len() as u64 != end - start {
            return Err(format!("the server sent {} bytes for a request of {}", bytes.len(), end - start));
        }
        hasher.update(&bytes);
        out.write_all(&bytes).map_err(|e| format!("cannot write the new file: {e}"))?;
        fetched(bytes.len() as u64);
        i = j;
    }
    Ok(crate::manifest::hex(&hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    /// Bytes that look like compressed data: no repeats a checksum could confuse.
    fn noise(len: usize, seed: u64) -> Vec<u8> {
        let mut state = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (0..len)
            .map(|_| {
                state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                (state >> 33) as u8
            })
            .collect()
    }

    fn temp(content: &[u8]) -> (PathBuf, File) {
        let path = std::env::temp_dir().join(format!("jpack-delta-{}-{}", std::process::id(), COUNTER.fetch_add(1, Ordering::SeqCst)));
        std::fs::write(&path, content).unwrap();
        let file = File::open(&path).unwrap();
        (path, file)
    }

    /// Runs the whole update of `old` to `new` with block size `bs`; returns the result and the bytes fetched.
    fn update(old: &[u8], new: &[u8], bs: usize) -> (Vec<u8>, u64, Vec<(u64, u64)>) {
        let index = Index::parse(&Index::build(new, bs).to_bytes()).unwrap();
        let (path, mut old_file) = temp(old);
        let local = find_local(&mut old_file, &index, &mut |_| {}).unwrap();
        let requests = RefCell::new(Vec::new());
        let mut result = Vec::new();
        let mut total = 0u64;
        let hash = assemble(
            &mut old_file,
            &local,
            &index,
            &mut result,
            &mut |start, end| {
                requests.borrow_mut().push((start, end));
                Ok(new[start as usize..end as usize].to_vec())
            },
            &mut |n| total += n,
        )
        .unwrap();
        assert_eq!(hash, crate::manifest::hex(&Sha256::digest(new)));
        assert_eq!(total, index.missing_bytes(&local));
        let _ = std::fs::remove_file(path);
        (result, total, requests.into_inner())
    }

    #[test]
    fn the_rolling_checksum_equals_the_checksum_computed_from_scratch() {
        let data = noise(5000, 1);
        let bs = 700;
        let (mut a, mut b) = sums(&data[..bs]);
        for pos in 1..(data.len() - bs) {
            let (out, incoming) = (data[pos - 1] as u32, data[pos - 1 + bs] as u32);
            a = a.wrapping_sub(out).wrapping_add(incoming) & 0xFFFF;
            b = b.wrapping_sub((bs as u32).wrapping_mul(out)).wrapping_add(a) & 0xFFFF;
            assert_eq!(a | (b << 16), weak_sum(&data[pos..pos + bs]), "at {pos}");
        }
    }

    #[test]
    fn an_index_is_read_back_and_damaged_ones_are_refused() {
        let index = Index::build(&noise(10_000, 2), 1024);
        assert_eq!(Index::parse(&index.to_bytes()).unwrap(), index);
        assert_eq!(index.blocks.len(), 10);
        assert_eq!(index.range(9), (9216, 10_000));

        let good = index.to_bytes();
        assert!(Index::parse(&good[..good.len() - 1]).is_err(), "cut off");
        let mut wrong_magic = good.clone();
        wrong_magic[0] = b'X';
        assert!(Index::parse(&wrong_magic).is_err());
        let mut wrong_size = good.clone();
        wrong_size[14] ^= 1;
        assert!(Index::parse(&wrong_size).is_err(), "size and block count disagree");
        let mut tiny_blocks = good;
        tiny_blocks[8..12].copy_from_slice(&16u32.to_le_bytes());
        assert!(Index::parse(&tiny_blocks).is_err());
        assert!(Index::parse(&[]).is_err());
    }

    #[test]
    fn the_same_file_needs_nothing_but_its_last_partial_block() {
        let data = noise(100_000, 3);
        let (result, fetched, requests) = update(&data, &data, 1024);
        assert_eq!(result, data);
        assert_eq!(fetched, 100_000 % 1024, "only the short last block");
        assert_eq!(requests.len(), 1);
    }

    #[test]
    fn bytes_inserted_in_the_middle_move_the_blocks_after_them_and_those_are_still_found() {
        let old = noise(300_000, 4);
        let mut new = old[..100_000].to_vec();
        new.extend_from_slice(&noise(1500, 99)); // inserted: everything after moves by 1500 bytes
        new.extend_from_slice(&old[100_000..]);
        let (result, fetched, _) = update(&old, &new, 1024);
        assert_eq!(result, new);
        assert!(fetched < 10_000, "only the blocks around the insertion and the tail are fetched, got {fetched}");
    }

    #[test]
    fn changed_regions_are_fetched_and_the_rest_is_reused() {
        let old = noise(400_000, 5);
        let mut new = old.clone();
        for byte in &mut new[50_000..51_000] {
            *byte ^= 0xFF;
        }
        for byte in &mut new[300_000..300_010] {
            *byte ^= 0x55;
        }
        let (result, fetched, requests) = update(&old, &new, 1024);
        assert_eq!(result, new);
        assert!(fetched <= 4 * 1024 + 400_000 % 1024, "got {fetched}");
        assert_eq!(requests.len(), 3, "the two changed regions and the last block: {requests:?}");
    }

    #[test]
    fn a_file_that_has_nothing_in_common_is_fetched_whole_and_correctly() {
        let old = noise(50_000, 6);
        let new = noise(70_000, 7);
        let (result, fetched, _) = update(&old, &new, 2048);
        assert_eq!(result, new);
        assert_eq!(fetched, 70_000);
    }

    #[test]
    fn a_file_smaller_than_a_block_and_an_empty_old_file_work() {
        let new = noise(300, 8);
        assert_eq!(update(&[], &new, 512).0, new);
        assert_eq!(update(&noise(10_000, 9), &new, 512).0, new);
        let big = noise(5000, 10);
        assert_eq!(update(&[], &big, 512).0, big);
    }

    #[test]
    fn long_runs_of_missing_blocks_are_fetched_in_several_requests() {
        let new = noise((MAX_RUN * 2 + 100_000) as usize, 11);
        let (result, _, requests) = update(&[], &new, 4096);
        assert_eq!(result, new);
        assert!(requests.len() >= 3, "{requests:?}");
        assert!(requests.iter().all(|(s, e)| e - s <= MAX_RUN + 4096));
    }

    #[test]
    fn a_wrong_answer_from_the_server_is_caught() {
        let new = noise(10_000, 12);
        let index = Index::build(&new, 1024);
        let (path, mut old) = temp(&[]);
        let local = find_local(&mut old, &index, &mut |_| {}).unwrap();
        let result = assemble(&mut old, &local, &index, &mut Vec::new(), &mut |_, _| Ok(vec![0; 5]), &mut |_| {});
        assert!(result.is_err());
        let _ = std::fs::remove_file(path);
    }

    /// The index the Java side must also produce for this input (see BlockIndexTest in the plugin).
    #[test]
    fn the_index_of_known_data_is_stable() {
        let data: Vec<u8> = (0..5000u32).map(|i| ((i * 31 + 7) % 251) as u8).collect();
        let index = Index::build(&data, 1024);
        let hex = crate::manifest::hex(&Sha256::digest(index.to_bytes()));
        println!("GOLDEN {hex}");
        assert_eq!(index.blocks.len(), 5);
        assert_eq!(hex.len(), 64);
    }
}
