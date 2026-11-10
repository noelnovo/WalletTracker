package win.downops.wallettracker.ui.importes

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import win.downops.wallettracker.data.models.Importe
import win.downops.wallettracker.databinding.ItemImporteBinding
import java.text.SimpleDateFormat
import java.util.Locale

class RViewImportesAdapter(
    private var items: List<Importe> = emptyList()
) : RecyclerView.Adapter<RViewImportesAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    inner class ViewHolder(val binding: ItemImporteBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImporteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val importe = items[position]
        holder.binding.txtConcept.text = importe.concept
        holder.binding.txtDate.text = dateFormat.format(importe.getDate())
        val amount = importe.amount
        holder.binding.txtAmount.text = String.format(Locale.getDefault(), "%.2f€", amount)
        holder.binding.txtAmount.setTextColor(
            if (amount < 0) 0xFFD32F2F.toInt() else 0xFF388E3C.toInt()
        )
    }

    override fun getItemCount() = items.size

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newItems: List<Importe>) {
        items = newItems
        notifyDataSetChanged()
    }
}
