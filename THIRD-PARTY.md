# Third-party software in the jpack stub

The jpack stub (`stub/`, the small native program installed next to an app) is written in Rust and is built from the crates below.
They are all under permissive licenses (MIT, Apache-2.0, ISC, BSD-3-Clause, Zlib, Unlicense, 0BSD, Unicode-3.0 and CDLA-Permissive-2.0),
which allow using and redistributing them in other software as long as their copyright and license notices are kept.
Each crate's own license text is in its source package (`https://crates.io/crates/<name>`).

Where a crate offers a choice (`MIT OR Apache-2.0`), either license may be used.

This list was generated from `Cargo.lock` for the Windows (`x86_64-pc-windows-gnu`) and Linux (`x86_64-unknown-linux-gnu`) builds and
includes build-time crates that are not part of the binary. Regenerate it whenever `stub/Cargo.lock` changes (see CLAUDE.md).

| Crate | Version | License |
|---|---|---|
| [adler2](https://crates.io/crates/adler2) | 2.0.1 | 0BSD OR MIT OR Apache-2.0 |
| [base64](https://crates.io/crates/base64) | 0.22.1 | MIT OR Apache-2.0 |
| [bitflags](https://crates.io/crates/bitflags) | 2.13.2 | MIT OR Apache-2.0 |
| [block-buffer](https://crates.io/crates/block-buffer) | 0.10.4 | MIT OR Apache-2.0 |
| [bumpalo](https://crates.io/crates/bumpalo) | 3.20.3 | MIT OR Apache-2.0 |
| [cc](https://crates.io/crates/cc) | 1.11.1 | MIT OR Apache-2.0 |
| [cfg-if](https://crates.io/crates/cfg-if) | 1.0.5 | MIT OR Apache-2.0 |
| [cpufeatures](https://crates.io/crates/cpufeatures) | 0.2.17 | MIT OR Apache-2.0 |
| [crc32fast](https://crates.io/crates/crc32fast) | 1.5.5 | MIT OR Apache-2.0 |
| [crypto-common](https://crates.io/crates/crypto-common) | 0.1.7 | MIT OR Apache-2.0 |
| [digest](https://crates.io/crates/digest) | 0.10.7 | MIT OR Apache-2.0 |
| [displaydoc](https://crates.io/crates/displaydoc) | 0.2.7 | MIT OR Apache-2.0 |
| [equivalent](https://crates.io/crates/equivalent) | 1.0.3 | Apache-2.0 OR MIT |
| [find-msvc-tools](https://crates.io/crates/find-msvc-tools) | 0.1.15 | MIT OR Apache-2.0 |
| [flate2](https://crates.io/crates/flate2) | 1.1.11 | MIT OR Apache-2.0 |
| [foreign-types](https://crates.io/crates/foreign-types) | 0.3.2 | MIT/Apache-2.0 |
| [foreign-types-shared](https://crates.io/crates/foreign-types-shared) | 0.1.1 | MIT/Apache-2.0 |
| [form_urlencoded](https://crates.io/crates/form_urlencoded) | 1.2.2 | MIT OR Apache-2.0 |
| [generic-array](https://crates.io/crates/generic-array) | 0.14.7 | MIT |
| [getrandom](https://crates.io/crates/getrandom) | 0.2.17 | MIT OR Apache-2.0 |
| [hashbrown](https://crates.io/crates/hashbrown) | 0.17.1 | MIT OR Apache-2.0 |
| [icu_collections](https://crates.io/crates/icu_collections) | 2.4.0 | Unicode-3.0 |
| [icu_locale_core](https://crates.io/crates/icu_locale_core) | 2.4.0 | Unicode-3.0 |
| [icu_normalizer](https://crates.io/crates/icu_normalizer) | 2.4.0 | Unicode-3.0 |
| [icu_normalizer_data](https://crates.io/crates/icu_normalizer_data) | 2.4.0 | Unicode-3.0 |
| [icu_properties](https://crates.io/crates/icu_properties) | 2.4.0 | Unicode-3.0 |
| [icu_properties_data](https://crates.io/crates/icu_properties_data) | 2.4.0 | Unicode-3.0 |
| [icu_provider](https://crates.io/crates/icu_provider) | 2.4.0 | Unicode-3.0 |
| [idna](https://crates.io/crates/idna) | 1.1.0 | MIT OR Apache-2.0 |
| [idna_adapter](https://crates.io/crates/idna_adapter) | 1.2.2 | Apache-2.0 OR MIT |
| [indexmap](https://crates.io/crates/indexmap) | 2.14.2 | Apache-2.0 OR MIT |
| [itoa](https://crates.io/crates/itoa) | 1.0.19 | MIT OR Apache-2.0 |
| [libc](https://crates.io/crates/libc) | 0.2.191 | MIT OR Apache-2.0 |
| [litemap](https://crates.io/crates/litemap) | 0.8.3 | Unicode-3.0 |
| [log](https://crates.io/crates/log) | 0.4.35 | MIT OR Apache-2.0 |
| [memchr](https://crates.io/crates/memchr) | 2.8.3 | Unlicense OR MIT |
| [miniz_oxide](https://crates.io/crates/miniz_oxide) | 0.9.1 | MIT OR Zlib OR Apache-2.0 |
| [native-tls](https://crates.io/crates/native-tls) | 0.2.14 | MIT OR Apache-2.0 |
| [once_cell](https://crates.io/crates/once_cell) | 1.21.4 | MIT OR Apache-2.0 |
| [openssl](https://crates.io/crates/openssl) | 0.10.82 | Apache-2.0 |
| [openssl-macros](https://crates.io/crates/openssl-macros) | 0.1.1 | MIT/Apache-2.0 |
| [openssl-probe](https://crates.io/crates/openssl-probe) | 0.1.6 | MIT/Apache-2.0 |
| [openssl-sys](https://crates.io/crates/openssl-sys) | 0.9.118 | MIT |
| [percent-encoding](https://crates.io/crates/percent-encoding) | 2.3.2 | MIT OR Apache-2.0 |
| [pkg-config](https://crates.io/crates/pkg-config) | 0.3.34 | MIT OR Apache-2.0 |
| [potential_utf](https://crates.io/crates/potential_utf) | 0.1.6 | Unicode-3.0 |
| [proc-macro2](https://crates.io/crates/proc-macro2) | 1.0.107 | MIT OR Apache-2.0 |
| [quote](https://crates.io/crates/quote) | 1.0.47 | MIT OR Apache-2.0 |
| [ring](https://crates.io/crates/ring) | 0.17.14 | Apache-2.0 AND ISC |
| [rustls](https://crates.io/crates/rustls) | 0.23.46 | Apache-2.0 OR ISC OR MIT |
| [rustls-pki-types](https://crates.io/crates/rustls-pki-types) | 1.16.0 | MIT OR Apache-2.0 |
| [rustls-webpki](https://crates.io/crates/rustls-webpki) | 0.103.16 | ISC |
| [schannel](https://crates.io/crates/schannel) | 0.1.27 | MIT |
| [serde](https://crates.io/crates/serde) | 1.0.229 | MIT OR Apache-2.0 |
| [serde_core](https://crates.io/crates/serde_core) | 1.0.229 | MIT OR Apache-2.0 |
| [serde_derive](https://crates.io/crates/serde_derive) | 1.0.229 | MIT OR Apache-2.0 |
| [serde_json](https://crates.io/crates/serde_json) | 1.0.154 | MIT OR Apache-2.0 |
| [sha2](https://crates.io/crates/sha2) | 0.10.9 | MIT OR Apache-2.0 |
| [shlex](https://crates.io/crates/shlex) | 2.0.1 | MIT OR Apache-2.0 |
| [simd-adler32](https://crates.io/crates/simd-adler32) | 0.3.10 | MIT |
| [smallvec](https://crates.io/crates/smallvec) | 1.16.5 | MIT OR Apache-2.0 |
| [stable_deref_trait](https://crates.io/crates/stable_deref_trait) | 1.2.1 | MIT OR Apache-2.0 |
| [subtle](https://crates.io/crates/subtle) | 2.6.1 | BSD-3-Clause |
| [syn](https://crates.io/crates/syn) | 3.0.10 | MIT OR Apache-2.0 |
| [syn](https://crates.io/crates/syn) | 2.0.119 | MIT OR Apache-2.0 |
| [synstructure](https://crates.io/crates/synstructure) | 0.14.0 | MIT |
| [tinystr](https://crates.io/crates/tinystr) | 0.8.5 | Unicode-3.0 |
| [typenum](https://crates.io/crates/typenum) | 1.20.1 | MIT OR Apache-2.0 |
| [unicode-ident](https://crates.io/crates/unicode-ident) | 1.0.27 | (MIT OR Apache-2.0) AND Unicode-3.0 |
| [untrusted](https://crates.io/crates/untrusted) | 0.9.0 | ISC |
| [ureq](https://crates.io/crates/ureq) | 2.12.1 | MIT OR Apache-2.0 |
| [url](https://crates.io/crates/url) | 2.5.8 | MIT OR Apache-2.0 |
| [utf_types](https://crates.io/crates/utf_types) | 1.0.0 | Apache-2.0 OR MIT |
| [utf8_iter](https://crates.io/crates/utf8_iter) | 1.1.0 | Apache-2.0 OR MIT |
| [vcpkg](https://crates.io/crates/vcpkg) | 0.2.15 | MIT/Apache-2.0 |
| [version_check](https://crates.io/crates/version_check) | 0.9.5 | MIT/Apache-2.0 |
| [webpki-roots](https://crates.io/crates/webpki-roots) | 0.26.11 | CDLA-Permissive-2.0 |
| [webpki-roots](https://crates.io/crates/webpki-roots) | 1.0.12 | CDLA-Permissive-2.0 |
| [windows_x86_64_gnu](https://crates.io/crates/windows_x86_64_gnu) | 0.52.6 | MIT OR Apache-2.0 |
| [windows-sys](https://crates.io/crates/windows-sys) | 0.59.0 | MIT OR Apache-2.0 |
| [windows-targets](https://crates.io/crates/windows-targets) | 0.52.6 | MIT OR Apache-2.0 |
| [writeable](https://crates.io/crates/writeable) | 0.6.4 | Unicode-3.0 |
| [yoke](https://crates.io/crates/yoke) | 0.8.4 | Unicode-3.0 |
| [yoke-derive](https://crates.io/crates/yoke-derive) | 0.8.4 | Unicode-3.0 |
| [zerofrom](https://crates.io/crates/zerofrom) | 0.1.8 | Unicode-3.0 |
| [zerofrom-derive](https://crates.io/crates/zerofrom-derive) | 0.1.8 | Unicode-3.0 |
| [zeroize](https://crates.io/crates/zeroize) | 1.9.1 | Apache-2.0 OR MIT |
| [zerotrie](https://crates.io/crates/zerotrie) | 0.2.5 | Unicode-3.0 |
| [zerovec](https://crates.io/crates/zerovec) | 0.11.9 | Unicode-3.0 |
| [zerovec-derive](https://crates.io/crates/zerovec-derive) | 0.11.7 | Unicode-3.0 |
| [zip](https://crates.io/crates/zip) | 2.6.1 | MIT |
| [zmij](https://crates.io/crates/zmij) | 1.0.23 | MIT |
| [zopfli](https://crates.io/crates/zopfli) | 0.8.4 | Apache-2.0 |

The jpack library and Maven plugin (`jpack-api`, `jpack-maven-plugin`) have no third-party runtime dependencies.
