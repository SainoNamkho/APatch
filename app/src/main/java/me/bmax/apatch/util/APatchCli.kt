package me.bmax.apatch.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import dev.utils.common.ZipUtils
import me.bmax.apatch.APApplication
import me.bmax.apatch.APApplication.Companion.SUPERCMD
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.Natives
import me.bmax.apatch.apApp
import me.bmax.apatch.ui.screen.MODULE_TYPE
import org.ini4j.Ini
import java.io.File
import java.io.StringReader
import java.util.UUID
import kotlin.concurrent.thread


private const val TAG = "APatchCli"

@Suppress("DEPRECATION")
private fun getKPatchPath(): String {
    return apApp.applicationInfo.nativeLibraryDir + File.separator + "libkpatch.so"
}

class RootShellInitializer : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        shell.newJob().add("export PATH=\$PATH:/system_ext/bin:/vendor/bin").exec()
        return true
    }
}

@Suppress("DEPRECATION")
fun createRootShell(): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create().setInitializers(RootShellInitializer::class.java)
    return try {
        builder.build(
            SUPERCMD, APApplication.superKey, "-Z", APApplication.MAGISK_SCONTEXT
        )
    } catch (e: Throwable) {
        Log.e(TAG, "su failed: ", e)
        try {
            Log.e(TAG, "retry compat kpatch su")
            return builder.build(
                getKPatchPath(),
                APApplication.superKey,
                "su",
                "-Z",
                APApplication.MAGISK_SCONTEXT
            )
        } catch (e: Throwable) {
            Log.e(TAG, "retry compat kpatch su failed: ", e)
            return builder.build("sh")
        }
    }
}

object APatchCli {
    var SHELL: Shell = createRootShell()
    fun refresh() {
        val tmp = SHELL
        SHELL = createRootShell()
        tmp.close()
    }
}

fun getRootShell(): Shell {
    return APatchCli.SHELL
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

@Suppress("DEPRECATION")
fun tryGetRootShell(): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        builder.build(
            SUPERCMD, APApplication.superKey, "-Z", APApplication.MAGISK_SCONTEXT
        )
    } catch (e: Throwable) {
        Log.e(TAG, "su failed: ", e)
        return try {
            Log.e(TAG, "retry compat kpatch su")
            builder.build(
                getKPatchPath(),
                APApplication.superKey,
                "su",
                "-Z",
                APApplication.MAGISK_SCONTEXT
            )
        } catch (e: Throwable) {
            Log.e(TAG, "retry kpatch su failed: ", e)
            return try {
                Log.e(TAG, "retry su: ", e)
                builder.build("su")
            } catch (e: Throwable) {
                Log.e(TAG, "retry su failed: ", e)
                builder.build("sh")
            }
        }
    }
}

fun shellForResult(shell: Shell, vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return shell.newJob().add(*cmds).to(out, err).exec()
}

fun rootShellForResult(vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return getRootShell().newJob().add(*cmds).to(out, err).exec()
}

fun execApd(args: String): Boolean {
    val shell = getRootShell()
    return ShellUtils.fastCmdResult(shell, "${APApplication.APD_PATH} $args")
}

fun listModules(): String {
    val shell = getRootShell()
    val out =
        shell.newJob().add("${APApplication.APD_PATH} module list").to(ArrayList(), null).exec().out
    return out.joinToString("\n").ifBlank { "[]" }
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execApd(cmd)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execApd(cmd)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

fun installKPM(file: File, shell: Shell, stdoutCallback: CallbackList<String?>, stderrCallback: CallbackList<String?>): Boolean {
    val randomDir = UUID.randomUUID().toString()
    val tmpUnzipDir: ExtendedFile =
        FileSystemManager.getLocal().getFile(apApp.filesDir.parent, randomDir)

    try {
        var ufiles = ZipUtils.unzipFile(file, tmpUnzipDir)
        ufiles.forEach { stdoutCallback.add(it.name) }
    } catch (e: Exception) {
        stderrCallback.add(e.toString())
        return false
    }

    val propFile = File(tmpUnzipDir, "module.prop")
    val ini = Ini(propFile)
    val name = ini["name"]

    if(name == null) {
        stderrCallback.add("Invalid name in module.prop")
        return false
    }

    val moduleDir = "${APApplication.KPMS_DIR}/$randomDir"

    val cmd = arrayOf(
        "rm -rf ${moduleDir}",
        "cp -rf ${moduleDir} ${tmpUnzipDir}"
    )
    val result = shell.newJob().add(*cmd).to(stdoutCallback, stderrCallback)
        .exec().isSuccess

    return result
}

fun installModule(
    uri: Uri, type: MODULE_TYPE, onFinish: (Boolean) -> Unit, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val resolver = apApp.contentResolver

    with(resolver.openInputStream(uri)) {
        val file = File(apApp.cacheDir, "module_" + type + ".zip")
        file.outputStream().use { output ->
            this?.copyTo(output)
        }

        val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                onStdout(s ?: "")
            }
        }

        val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                onStderr(s ?: "")
            }
        }

        val shell = getRootShell()

        var result = false
        if(type == MODULE_TYPE.APM) {
            val cmd = "${APApplication.APD_PATH} module install ${file.absolutePath}"
            result = shell.newJob().add("$cmd").to(stdoutCallback, stderrCallback)
                .exec().isSuccess
        } else {
            result = installKPM(file, shell, stdoutCallback, stderrCallback)
        }

        Log.i(TAG, "install $type module $uri result: $result")

        file.delete()

        onFinish(result)
        return result
    }
}

fun reboot(reason: String = "") {
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        getRootShell().newJob().add("/system/bin/input keyevent 26").exec()
    }
    getRootShell().newJob()
        .add("/system/bin/svc power reboot $reason || /system/bin/reboot $reason").exec()
}

fun overlayFsAvailable(): Boolean {
    val shell = getRootShell()
    return ShellUtils.fastCmdResult(shell, "cat /proc/filesystems | grep overlay")
}

fun hasMagisk(): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("nsenter --mount=/proc/1/ns/mnt which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isGlobalNamespaceEnabled(): Boolean {
    val shell = getRootShell()
    val result = ShellUtils.fastCmd(shell, "cat ${APApplication.GLOBAL_NAMESPACE_FILE}")
    Log.i(TAG, "is global namespace enabled: $result")
    return result == "1"
}

fun setGlobalNamespaceEnabled(value: String) {
    getRootShell().newJob().add("echo $value > ${APApplication.GLOBAL_NAMESPACE_FILE}")
        .submit { result ->
            Log.i(TAG, "setGlobalNamespaceEnabled result: ${result.isSuccess} [${result.out}]")
        }
}

fun forceStopApp(packageName: String) {
    val shell = getRootShell()
    val result = shell.newJob().add("am force-stop $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String) {
    val shell = getRootShell()
    val result =
        shell.newJob().add("monkey -p $packageName -c android.intent.category.LAUNCHER 1").exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String) {
    forceStopApp(packageName)
    launchApp(packageName)
}

fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}
