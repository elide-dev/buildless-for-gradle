/* Copyright (c) 2023 Elide Ventures LLC
 *
 * This is private computer source code. This code is part of an application which is licensed privately, as part of
 * intellectual property owned by Elide Ventures, LLC. All rights are reserved. Viewing and editing this code implies
 * agreement with the Elide Non-Disclosure Agreement and Elide Inventions Assignment Agreement.
 *
 * Code bearing this header may not be shared outside of authorized circumstances without prior written consent from
 * authorized corporate officers of Elide Ventures, LLC.
 */

package build.less.plugin.gradle

import org.gradle.api.initialization.Settings
import org.gradle.caching.configuration.BuildCacheConfiguration
import org.gradle.caching.http.HttpBuildCache
import org.gradle.caching.local.DirectoryBuildCache
import java.net.URI

/**
 * # Buildless for Gradle: Plugin Configuration
 *
 * @param apiKey API key to use for interactions with the remote build cache service.
 * @param transport Transport mechanism to use when interacting with the build cache.
 * @param debug Whether to enable debug logging.
 * @param telemetry Whether to enable build telemetry integration.
 * @param reportErrors Whether to allow error reporting back to Buildless.
 * @param localCache Local build cache settings to apply.
 * @param remoteCache Remote build cache settings to apply.
 */
internal data class BuildlessPluginConfig(
  val apiKey: ApiKey,
  val transport: CacheTransport,
  val debug: Boolean,
  val telemetry: Boolean,
  val reportErrors: Boolean,
  val localCache: ImmutableLocalCacheSettings,
  val remoteCache: ImmutableRemoteCacheSettings,
) : java.io.Serializable {
  /** Immutable settings for local build caching. */
  internal class ImmutableLocalCacheSettings(
    override val enabled: Boolean,
    override val directory: String?,
  ) : BuildlessExtensionAPI.LocalCacheSettings {
    // Apply these settings to the local Gradle build cache settings.
    internal fun applyTo(target: DirectoryBuildCache) {
      if (!directory.isNullOrBlank()) {
        target.directory = directory
      }
    }

    internal companion object {
      /** @return Built immutable local cache settings copied from the [other] settings. */
      @JvmStatic internal fun from(other: BuildlessExtensionAPI.LocalCacheSettings): ImmutableLocalCacheSettings =
        ImmutableLocalCacheSettings(
          enabled = other.enabled,
          directory = other.directory,
        )
    }
  }

  /** Immutable settings for remote build caching. */
  internal data class ImmutableRemoteCacheSettings(
    private val apiKeyProvider: () -> ApiKey?,
    override val enabled: Boolean,
    override val push: Boolean,
  ) : BuildlessExtensionAPI.RemoteCacheSettings {
    // Apply these settings to the local Gradle build cache settings.
    internal fun applyTo(target: HttpBuildCache) = when (val apiKey = apiKeyProvider.invoke()) {
      null -> {
        // nothing to apply; no credentials available
      }
      else -> target.credentials {
        it.username = "apikey"
        it.password = apiKey.key
      }
    }.also {
      target.isPush = true
      target.isAllowInsecureProtocol = false
      target.isAllowUntrustedServer = false
      target.isUseExpectContinue = true
      target.url = URI.create(Constants.DEFAULT_CACHE_ENDPOINT)
    }

    internal companion object {
      /** @return Built immutable local cache settings copied from the [other] settings. */
      @JvmStatic internal fun from(
        other: BuildlessExtensionAPI.RemoteCacheSettings,
        keyProvider: () -> ApiKey?,
      ): ImmutableRemoteCacheSettings = ImmutableRemoteCacheSettings(
        apiKeyProvider = keyProvider,
        enabled = other.enabled,
        push = other.push,
      )
    }
  }

  // Internal function to apply current settings to the target Gradle build cache configuration.
  internal fun applyTo(context: Settings, settings: BuildCacheConfiguration) = context.gradle.settingsEvaluated {
    context.buildCache.apply {
      // apply local caching configuration
      when {
        settings.local.isEnabled -> {
          // skip configuration of local caching which is already enabled
        }

        localCache.enabled -> local {
          it.isEnabled = true
          localCache.applyTo(it)
        }
      }

      // apply remote caching configuration
      when (settings.remote) {
        // if we don't have any settings, apply our own remote settings
        null -> remote(HttpBuildCache::class.java) {
          it.isEnabled = remoteCache.enabled
          remoteCache.applyTo(it)
        }

        // otherwise, let the user's settings prevail
        else -> {}
      }
    }
  }
}
