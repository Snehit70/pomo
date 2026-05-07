// app/src/main/java/com/pomoremote/ui/HistoryFragment.kt
package com.pomoremote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pomoremote.R
import com.pomoremote.db.DayStatsEntity
import com.pomoremote.db.HistoryCacheRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: HistoryAdapter
    private var historyCacheRepository: HistoryCacheRepository? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.historyRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = HistoryAdapter()
        recyclerView.adapter = adapter

        context?.let {
            historyCacheRepository = HistoryCacheRepository(it)
        }

        observeHistory()
    }

    private fun observeHistory() {
        val repo = historyCacheRepository ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeDayStats().collectLatest { entities ->
                val historyList = convertToHistoryList(entities)
                adapter.submitList(historyList)
            }
        }
    }

    private fun convertToHistoryList(entities: List<DayStatsEntity>): List<HistoryItem> {
        return entities.map { entity ->
            HistoryItem(
                date = entity.date,
                entry = DayEntry(
                    completed = entity.completed,
                    work_minutes = entity.workMinutes,
                    break_minutes = entity.breakMinutes
                )
            )
        }.sortedByDescending { it.date }
    }
}

data class DayEntry(
    val completed: Int,
    val work_minutes: Int,
    val break_minutes: Int
)

data class HistoryItem(val date: String, val entry: DayEntry)

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    private var list: List<HistoryItem> = emptyList()

    fun submitList(newList: List<HistoryItem>) {
        list = newList
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvFocus: TextView = view.findViewById(R.id.tvFocusTime)
        val tvBreak: TextView = view.findViewById(R.id.tvBreakTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        // Format date nicely
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
            val date = inputFormat.parse(item.date)
            holder.tvDate.text = date?.let { outputFormat.format(it) } ?: item.date
        } catch (e: Exception) {
            holder.tvDate.text = item.date
        }

        val hours = item.entry.work_minutes / 60
        val mins = item.entry.work_minutes % 60
        holder.tvFocus.text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        holder.tvBreak.text = "${item.entry.completed} sessions"
    }

    override fun getItemCount() = list.size
}
