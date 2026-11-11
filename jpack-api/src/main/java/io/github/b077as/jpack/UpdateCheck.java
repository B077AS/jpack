package io.github.b077as.jpack;

/**
 * What {@link Jpack#check()} found.
 *
 * @param available    true when a release other than the installed one is published
 * @param version      the published version (the installed one when nothing is available)
 * @param downloadSize bytes a download would fetch: only the parts that changed
 * @param downloaded   true when that release is already downloaded and only waits for the app to exit
 */
public record UpdateCheck(boolean available, String version, long downloadSize, boolean downloaded) {
}
