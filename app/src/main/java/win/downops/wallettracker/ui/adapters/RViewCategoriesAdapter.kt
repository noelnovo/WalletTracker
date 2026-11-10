package win.downops.wallettracker.ui.adapters

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import win.downops.wallettracker.R
import win.downops.wallettracker.data.models.ExpenseCategory

class RViewCategoriesAdapter(var list: MutableList<ExpenseCategory>) :
    RecyclerView.Adapter<RViewCategoriesAdapter.ExpenseCategoryViewHolder>() {

    class ExpenseCategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val categoryName: TextView = itemView.findViewById(R.id.label_name)
        val categoryTotal: TextView = itemView.findViewById(R.id.label_total)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseCategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.element_rviewcategory, parent, false)
        return ExpenseCategoryViewHolder(view)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    override fun getItemId(position: Int): Long {
        return list[position].id
    }

    @SuppressLint("DefaultLocale", "SetTextI18n")
    override fun onBindViewHolder(holder: ExpenseCategoryViewHolder, position: Int) {

        val category = list[position]
        holder.categoryName.text = category.name
        holder.categoryTotal.text = String.format("%.2f", category.total) + "€"

        holder.itemView.setOnClickListener {
            val bundle = Bundle()
            bundle.putLong("catId", category.id)
            holder.itemView.findNavController().navigate(R.id.nav_categoriesexpenses, bundle)
        }
    }

    fun removeItem(position: Int) {
        if (position in list.indices) {
            list.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, list.size)
        }
    }

    fun addItem(position: Int, category:ExpenseCategory){
        list.add(category)
        notifyItemInserted(position)
    }

    fun updateData(newCategories: List<ExpenseCategory>) {
        list = mutableListOf()
        list.addAll(newCategories)
        notifyItemChanged(0, null)
    }
}
