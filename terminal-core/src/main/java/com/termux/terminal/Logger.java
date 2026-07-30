package com.termux.terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Logger {

    // Vendored-code shim: the upstream Termux build falls back to android.util.Log
    // when no TerminalSessionClient is attached. This module is pure-JVM (shared by
    // the Android app and the headless server-side emulator), so the fallback writes
    // to System.err instead. On Android a real client is always attached, so this
    // branch is only exercised by the client-less server grid.
    private static void fallback(char level, String logTag, String message) {
        System.err.println(level + "/" + logTag + ": " + message);
    }

    public static void logError(TerminalSessionClientBase client, String logTag, String message) {
        if (client != null)
            client.logError(logTag, message);
        else
            fallback('E', logTag, message);
    }

    public static void logWarn(TerminalSessionClientBase client, String logTag, String message) {
        if (client != null)
            client.logWarn(logTag, message);
        else
            fallback('W', logTag, message);
    }

    public static void logInfo(TerminalSessionClientBase client, String logTag, String message) {
        if (client != null)
            client.logInfo(logTag, message);
        else
            fallback('I', logTag, message);
    }

    public static void logDebug(TerminalSessionClientBase client, String logTag, String message) {
        if (client != null)
            client.logDebug(logTag, message);
        else
            fallback('D', logTag, message);
    }

    public static void logVerbose(TerminalSessionClientBase client, String logTag, String message) {
        if (client != null)
            client.logVerbose(logTag, message);
        else
            fallback('V', logTag, message);
    }

    public static void logStackTraceWithMessage(TerminalSessionClientBase client, String tag, String message, Throwable throwable) {
        logError(client, tag, getMessageAndStackTraceString(message, throwable));
    }

    public static String getMessageAndStackTraceString(String message, Throwable throwable) {
        if (message == null && throwable == null)
            return null;
        else if (message != null && throwable != null)
            return message + ":\n" + getStackTraceString(throwable);
        else if (throwable == null)
            return message;
        else
            return getStackTraceString(throwable);
    }

    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return null;

        String stackTraceString = null;

        try {
            StringWriter errors = new StringWriter();
            PrintWriter pw = new PrintWriter(errors);
            throwable.printStackTrace(pw);
            pw.close();
            stackTraceString = errors.toString();
            errors.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return stackTraceString;
    }

}
