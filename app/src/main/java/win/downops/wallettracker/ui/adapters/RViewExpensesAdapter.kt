package win.downops.wallettracker.ui.adapters

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import win.downops.wallettracker.R
import win.downops.wallettracker.data.models.Expense
import java.text.SimpleDateFormat
import java.util.Locale

class RViewExpensesAdapter(var list: MutableList<Expense>) : RecyclerView.Adapter<RViewExpensesAdapter.ExpenseViewHolder>() {

    class ExpenseViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        val expensePrice: TextView = itemView.findViewById(R.id.label_price)
        val expenseDate: TextView = itemView.findViewById(R.id.label_date)
        val expenseDescription: TextView = itemView.findViewById(R.id.label_description)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.element_rviewexpense, parent, false)
        return ExpenseViewHolder(view)
    }

    override fun getItemCount(): Int {
        return list.size
    }
    @SuppressLint("DefaultLocale", "SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.expensePrice.text = String.format("%.2f", list[position].getPrice())+"€"
        val format = SimpleDateFormat("EEEE dd 'de' MMMM 'del' yyyy", Locale.forLanguageTag("es-ES"))
        holder.expenseDate.text = format.format(list[position].getDate())
        val desc = list[position].description
        if (desc.isNullOrEmpty()) {
            holder.expenseDescription.visibility = View.GONE
        } else if (desc.length > 20) {
            holder.expenseDescription.text = desc.substring(0, 20) + "..."
        }else {
            holder.expenseDescription.text = desc
        }


        holder.itemView.setOnClickListener {
            val expenseId = list[position].id
            val categoryId = list[position].getCategoryId()
            val bundle = Bundle()
            bundle.putLong("catId", categoryId)
            bundle.putLong("expenseId", expenseId)
            holder.itemView.findNavController().navigate(R.id.nav_createexpense, bundle)
        }

    }
    fun removeItem(position: Int) {
        if (position in list.indices) {
            list.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, list.size)
        }
    }
    fun addItem(position: Int, expense:Expense){
        list.add(expense)
        notifyItemInserted(position)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(expenses: List<Expense>) {
        list.clear()
        list.addAll(expenses)
        notifyDataSetChanged()
    }
}