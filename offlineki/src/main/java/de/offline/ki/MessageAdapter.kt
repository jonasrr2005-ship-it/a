package de.offline.ki

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import de.offline.ki.databinding.ItemMessageBinding

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.Holder>() {

    class Holder(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = messages.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val m = messages[position]
        val ctx = h.b.root.context
        val user = m.role == "user"
        val shown = if (user) m.text else ChatEngine.cleanReply(m.text)
        h.b.text.text = shown.ifEmpty { "…" }
        (h.b.text.layoutParams as FrameLayout.LayoutParams).gravity = if (user) Gravity.END else Gravity.START
        h.b.text.background.mutate().setTint(
            ContextCompat.getColor(ctx, if (user) R.color.bubble_user else R.color.bubble_ai)
        )
        h.b.text.setTextColor(
            ContextCompat.getColor(ctx, if (user) R.color.bubble_user_text else R.color.bubble_ai_text)
        )
    }
}
