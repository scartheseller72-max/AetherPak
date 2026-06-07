// IUserService.aidl
package com.zorg.aetherpak.access;

import android.os.ParcelFileDescriptor;

/**
 * Remote interface implemented by the Shizuku UserService process (running with ADB/UID 2000
 * privileges). The ShizukuAccessProvider binds this to run shell commands and to open file
 * descriptors for OBB / shared-media paths that the app process itself cannot reach.
 */
interface IUserService {

    /** Called by the system when the service is being torn down. */
    void destroy() = 16777114;

    /**
     * Run a command line via `sh -c`. Returns a flat String result with this exact framing:
     *   line 0           : "EXIT=<code>"
     *   line 1           : "STDOUT" marker
     *   ... stdout lines ...
     *   line k           : "STDERR" marker
     *   ... stderr lines ...
     * The provider parses this back into a ShellResult.
     */
    String exec(String command) = 1;

    /** Open a read-only ParcelFileDescriptor for an absolute path reachable by the shell UID. */
    ParcelFileDescriptor openRead(String path) = 2;

    /** Open a write (truncating, or append) ParcelFileDescriptor for an absolute path. */
    ParcelFileDescriptor openWrite(String path, boolean append) = 3;
}
