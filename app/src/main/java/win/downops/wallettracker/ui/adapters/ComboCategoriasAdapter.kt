package win.downops.wallettracker.ui.adapters
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import win.downops.wallettracker.R
import win.downops.wallettracker.data.models.ExpenseCategory

class ComboCategoriasAdapter(private val context: Context, private val items: List<ExpenseCategory>) : BaseAdapter() {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): ExpenseCategory = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.element_combocategorias, parent, false)

        val item = getItem(position)

        val text = view.findViewById<TextView>(R.id.spinner_text)
        text.text = item.name

        return view
    }
    fun getById(catId: Long): Int{
        var position = 0
        for (item in items){
            if(item.id == catId)
                return position
            position ++
        }
        return -1
    }
}