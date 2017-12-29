package org.runestar.client.plugins

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.WatchKey
import java.util.concurrent.ExecutorService

internal class PluginHolder<T : PluginSettings>(
        private val plugin: Plugin<T>,
        private val watchKey: WatchKey,
        private val executor: ExecutorService,
        private val settingsReadWriter: FileReadWriter
) : PluginHandle {

    private companion object {
        const val LOG_APPENDER_NAME = "plugin-individual"
        const val LOG_ENCODER_PATTERN = "%date{ISO8601} [%thread] %-5level - %msg%n"
    }

    override val directory = watchKey.watchable() as Path

    private val logger get() = plugin.logger

    override val settingsFile: Path =
            directory.resolve("${PluginHandle.SETTINGS_FILE_NAME_BASE}.${settingsReadWriter.fileExtension}")

    private var ignoreNextEvent = false

    override val name: String get() = plugin.javaClass.name

    override val logFile: Path get() = directory.resolve(PluginHandle.LOG_FILE_NAME)

    init {
        addIndividualFileLogger()
        plugin.directory = directory
        createSettings()
        if (isRunning) {
            startPlugin()
        }
    }

    override fun start() {
        executor.submit {
            if (isRunning) return@submit
            plugin.settings.enabled = true
            startPlugin()
            writeSettings()
        }
    }

    override fun stop() {
        executor.submit {
            if (!isRunning) return@submit
            plugin.settings.enabled = false
            writeSettings()
            stopPlugin()
        }
    }

    override val isRunning: Boolean get() {
        return plugin.settings.enabled
    }

    private fun writeSettings() {
        try {
            ignoreNextEvent = true
            logger.info("Writing settings...")
            settingsReadWriter.write(settingsFile, plugin.settings)
            logger.info("Write successful.")
        } catch (e: IOException) {
            logger.warn("Write failed.", e)
            if (isRunning) {
                stopPlugin()
                plugin.settings.enabled = false
            }
        }
    }

    private fun readSettings() {
        try {
            plugin.settings = settingsReadWriter.read(settingsFile, plugin.defaultSettings.javaClass)
            logger.info("Read successful.")
        } catch (e: IOException) {
            logger.warn("Read failed. Reverting to default settings.", e)
            plugin.settings = plugin.defaultSettings
            writeSettings()
        }
    }

    private fun createSettings() {
        if (Files.exists(settingsFile)) {
            logger.info("Settings file exists. Reading...")
            readSettings()
        } else {
            logger.info("Settings file does not exist. Using default settings.")
            plugin.settings = plugin.defaultSettings
            writeSettings()
        }
    }

    internal fun settingsFileChanged() {
        if (ignoreNextEvent) {
            // ignore events caused by this class writing
            ignoreNextEvent = false
            return
        }
        if (isRunning) stopPlugin()
        if (Files.notExists(settingsFile)) {
            logger.info("Settings file missing. Switching to default settings.")
            plugin.settings = plugin.defaultSettings
            writeSettings()
        } else {
            logger.info("Settings file modified. Reading new settings...")
            readSettings()
        }
        if (isRunning) {
            startPlugin()
        }
    }

    internal fun destroy() {
        watchKey.cancel()
        if (isRunning) {
            stopPlugin()
        }
    }

    private fun startPlugin() {
        try {
            plugin.start()
        } catch (e: Exception) {
            logger.warn("Exception starting plugin.", e)
            stopPlugin()
            plugin.settings.enabled = false
        }
    }

    private fun stopPlugin() {
        try {
            plugin.stop()
        } catch (e: Exception) {
            logger.warn("Exception stopping plugin.", e)
            plugin.settings.enabled = false
        }
    }

    override fun toString(): String {
        return name
    }

    private fun addIndividualFileLogger() {
        val lblogger = logger as Logger
        if (lblogger.getAppender(LOG_APPENDER_NAME) != null) return
        lblogger.level = Level.ALL
        val logCtx = LoggerFactory.getILoggerFactory() as LoggerContext

        val logEncoder = PatternLayoutEncoder()
        logEncoder.context = logCtx
        logEncoder.pattern = LOG_ENCODER_PATTERN
        logEncoder.start()

        val logFileAppender = RollingFileAppender<ILoggingEvent>()
        logFileAppender.context = logCtx
        logFileAppender.name = LOG_APPENDER_NAME
        logFileAppender.encoder = logEncoder
        logFileAppender.isAppend = true
        logFileAppender.file = directory.resolve(PluginHandle.LOG_FILE_NAME).toString()

        val rollingPolicy = FixedWindowRollingPolicy()
        rollingPolicy.minIndex = 0
        rollingPolicy.maxIndex = 0
        rollingPolicy.context = logCtx
        rollingPolicy.setParent(logFileAppender)
        rollingPolicy.fileNamePattern = directory.resolve(PluginHandle.LOG_FILE_NAME + "%i").toString()
        rollingPolicy.start()

        val triggeringPolicy = SizeBasedTriggeringPolicy<ILoggingEvent>()
        triggeringPolicy.setMaxFileSize(FileSize.valueOf("3MB"))
        triggeringPolicy.context = logCtx
        triggeringPolicy.start()

        logFileAppender.rollingPolicy = rollingPolicy
        logFileAppender.triggeringPolicy = triggeringPolicy
        logFileAppender.start()

        lblogger.addAppender(logFileAppender)
    }
}