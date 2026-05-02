package ai.opencyvis.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.ChatHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationDetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var historyRepo: ChatHistoryRepository
    private var conversationId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_detail)

        historyRepo = ChatHistoryRepository(this)
        conversationId = intent.getLongExtra("conversation_id", -1)
        val title = intent.getStringExtra("conversation_title") ?: ""
        val status = intent.getStringExtra("conversation_status") ?: ""
        val createdAt = intent.getLongExtra("conversation_created_at", 0)

        findViewById<TextView>(R.id.text_title).text = title
        findViewById<TextView>(R.id.text_subtitle).text = buildSubtitle(createdAt, status)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        val chatAdapter = ChatAdapter()
        findViewById<RecyclerView>(R.id.recycler_messages).apply {
            layoutManager = LinearLayoutManager(this@ConversationDetailActivity)
            adapter = chatAdapter
        }

        scope.launch {
            val messages = historyRepo.getMessages(conversationId)
            messages.forEach { entity ->
                val type = try { MessageType.valueOf(entity.type) } catch (_: Exception) { MessageType.SYSTEM }
                chatAdapter.addMessage(ChatMessage(type, entity.text, entity.timestamp))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildSubtitle(createdAt: Long, status: String): String {
        val time = DateUtils.getRelativeTimeSpanString(
            createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val statusLabel = when (status) {
            "completed" -> "Completed"
            "failed" -> "Failed"
            "stopped" -> "Stopped"
            "running" -> "Running"
            else -> status
        }
        return "$time · $statusLabel"
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this conversation?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    historyRepo.deleteConversation(conversationId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
