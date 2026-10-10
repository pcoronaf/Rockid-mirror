package com.rokidmirror.sender.privilege

/**
 * Wire contract between the app and its own shell-level helper process.
 *
 * Computer mode needs two things an ordinary app cannot do: create a *trusted* virtual display
 * (so other apps can be launched onto it and input injected there) and inject input events onto
 * a display the app does not own. Both are allowed for the `shell` user. The app therefore pairs
 * with the phone's own Wireless debugging endpoint, launches a small helper through
 * `app_process` (so it runs as `shell`), and the helper hands a Binder back to the app. The app
 * then forwards system-service transactions through that Binder, which execute with shell
 * identity. Nothing here grants the app new permissions; it borrows the helper's for the calls
 * that need them, and the helper does nothing except forward those calls and run commands.
 *
 * The handshake, the transaction framing and the SHELL_UID gate are adapted from Dextop
 * (GPL-3.0, github.com/NarYuki/Dextop); see docs/dependencies.md and NOTICE.
 */
object ShellProtocol {
    /** Interface token the helper enforces on every transaction, so stray callers are rejected. */
    const val DESCRIPTOR = "com.rokidmirror.sender.privilege.RUNTIME"

    /** Forward a transaction to a named framework service ("display", "input", ...). */
    const val TRANSACT_SERVICE = 0x524B4D01 // 'R' 'K' 'M' 01

    /** Run a command line as the shell user and return exit code, stdout and stderr. */
    const val EXECUTE_COMMAND = 0x524B4D02

    /** ContentProvider method the helper calls to hand its Binder to the app. */
    const val PROVIDER_METHOD = "publishRuntime"

    /** Bundle key carrying the helper's Binder in the publish call. */
    const val PROVIDER_BINDER_KEY = "runtimeBinder"

    /** Bundle key carrying the app's lifecycle Binder back to the helper. */
    const val PROVIDER_LIFECYCLE_KEY = "lifecycleBinder"

    /** Appended to the application id to form the provider authority the helper publishes into. */
    const val PROVIDER_AUTHORITY_SUFFIX = ".shellbridge"

    /** Entry point the `app_process` command names; must stay in sync with the launch command. */
    const val HELPER_MAIN_CLASS = "com.rokidmirror.sender.privilege.ShellHelperMain"

    fun providerAuthority(packageName: String): String = packageName + PROVIDER_AUTHORITY_SUFFIX
}
