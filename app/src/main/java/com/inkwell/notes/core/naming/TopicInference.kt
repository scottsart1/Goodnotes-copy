package com.inkwell.notes.core.naming

import com.inkwell.notes.core.model.AiException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Decides a document's topic from its content: the user's AI model when one is configured
 * (strict prompt, tiny output), otherwise, or after repeated transient failures, the
 * on-device [LocalTopicExtractor]. Pure apart from the injected [AiNamer], so it is unit-tested.
 */
class TopicInference(private val local: LocalTopicExtractor = LocalTopicExtractor()) {

    /** Sends one naming request and returns the model's raw reply; throws on failure. */
    fun interface AiNamer {
        suspend fun complete(system: String, user: String): String
    }

    /**
     * @param ai null when AI is unavailable or disabled for naming.
     * @param aiFailures transient AI failures recorded for this document so far.
     * @param userRequested true for "rename now": never defers, falls back to local at once.
     */
    suspend fun infer(content: NamingContent, ai: AiNamer?, aiFailures: Int, userRequested: Boolean): TopicOutcome {
        if (content.isBlank) return TopicOutcome.Insufficient
        val meaningful = local.meaningfulTokenCount(content)
        if (meaningful < MIN_TOKENS_FOR_AI) return TopicOutcome.Insufficient

        if (ai != null && (userRequested || aiFailures < AutoNamingRules.MAX_AI_FAILURES)) {
            val reply = try {
                ai.complete(NamingPrompt.SYSTEM, NamingPrompt.userMessage(content, local.keyTerms(content)))
            } catch (c: CancellationException) {
                throw c
            } catch (e: AiException) {
                if (e.retryable && !userRequested) return TopicOutcome.RetryLater
                null
            } catch (e: IOException) {
                if (!userRequested) return TopicOutcome.RetryLater
                null
            } catch (e: Exception) {
                // Unknown provider/adapter failure: treat as transient in the background.
                if (!userRequested) return TopicOutcome.RetryLater
                null
            }
            if (reply != null) {
                when (val parsed = NamingPrompt.parseReply(reply)) {
                    is NamingPrompt.Reply.Topic -> return TopicOutcome.Found(parsed.topic, TopicOutcome.Source.AI)
                    NamingPrompt.Reply.Insufficient -> return TopicOutcome.Insufficient
                    NamingPrompt.Reply.Unusable -> Unit // generic or malformed answer: try on-device inference
                }
            }
        }
        val topic = local.extract(content) ?: return TopicOutcome.Insufficient
        return TopicOutcome.Found(topic, TopicOutcome.Source.LOCAL)
    }

    companion object {
        /** Below this many meaningful words not even a model can tell what the notes are about. */
        const val MIN_TOKENS_FOR_AI = 3
    }
}
