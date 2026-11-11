package io.github.b077as.jpack;

/** Follows a download. Called at most about ten times a second, and once more when the download is complete. */
@FunctionalInterface
public interface ProgressListener {

    /**
     * @param received bytes downloaded so far
     * @param total    bytes to download in all (0 when the release changes no files, only its settings)
     */
    void progress(long received, long total);
}
