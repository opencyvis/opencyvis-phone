package ai.opencyvis.config

object ConfigDeepLink {
    data class ImportedConfig(
        val provider: String,
        val apiKey: String,
        val model: String,
        val baseUrl: String,
        val maxSteps: Int?
    )

    fun parse(params: Map<String, String?>): Result<ImportedConfig> {
        val provider = params["provider"]?.trim()?.lowercase().orEmpty()
            .ifEmpty { ConfigRepository.PROVIDER_OPENAI }

        if (provider !in supportedProviders) {
            return Result.failure(IllegalArgumentException("Unsupported provider: $provider"))
        }

        val apiKey = firstParam(params, "api_key", "key")?.trim().orEmpty()
        if (provider != ConfigRepository.PROVIDER_OLLAMA && apiKey.isEmpty()) {
            return Result.failure(IllegalArgumentException("API key is required for $provider"))
        }

        val maxSteps = params["max_steps"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 1 }
                ?: return Result.failure(IllegalArgumentException("max_steps must be a positive integer"))
        }

        val (defaultModel, defaultBaseUrl) = defaultsFor(provider)
        return Result.success(
            ImportedConfig(
                provider = provider,
                apiKey = apiKey,
                model = params["model"]?.trim()?.ifEmpty { null } ?: defaultModel,
                baseUrl = firstParam(params, "base_url", "url")?.trim()?.ifEmpty { null } ?: defaultBaseUrl,
                maxSteps = maxSteps
            )
        )
    }

    fun redactedApiKey(apiKey: String): String {
        if (apiKey.isBlank()) return "(empty)"
        if (apiKey.length <= 8) return "..."
        return "${apiKey.take(3)}...${apiKey.takeLast(4)}"
    }

    private fun defaultsFor(provider: String): Pair<String, String> = when (provider) {
        ConfigRepository.PROVIDER_ANTHROPIC ->
            ConfigRepository.DEFAULT_ANTHROPIC_MODEL to ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL
        ConfigRepository.PROVIDER_OLLAMA ->
            ConfigRepository.DEFAULT_OLLAMA_MODEL to ConfigRepository.DEFAULT_OLLAMA_BASE_URL
        else -> ConfigRepository.DEFAULT_MODEL to ConfigRepository.DEFAULT_BASE_URL
    }

    private fun firstParam(params: Map<String, String?>, vararg names: String): String? =
        names.firstNotNullOfOrNull { params[it] }

    private val supportedProviders = setOf(
        ConfigRepository.PROVIDER_OPENAI,
        ConfigRepository.PROVIDER_ANTHROPIC,
        ConfigRepository.PROVIDER_OLLAMA
    )
}
