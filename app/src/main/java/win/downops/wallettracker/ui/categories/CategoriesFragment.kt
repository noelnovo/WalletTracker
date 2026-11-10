package win.downops.wallettracker.ui.categories

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import win.downops.wallettracker.R
import win.downops.wallettracker.databinding.FragmentCategoriesBinding
import win.downops.wallettracker.ui.adapters.RViewCategoriesAdapter
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import win.downops.wallettracker.MainActivity
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.ExpenseCategory
import win.downops.wallettracker.util.AppResultHandler
import win.downops.wallettracker.util.Logger
import win.downops.wallettracker.util.Messages.unexpectedError
import java.util.Collections

@AndroidEntryPoint
class CategoriesFragment : Fragment() {
    private val viewModel: CategoriesViewModel by viewModels()
    private var _binding: FragmentCategoriesBinding? = null
    private val binding get() = _binding!!
    private var snackbar: Snackbar? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try{
            super.onViewCreated(view, savedInstanceState)
            initObservers()

            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner
            ) {
                showLogoutConfirmationDialog()
            }

        }catch(e: Exception){
            Logger.log(e)
        }
    }

    private fun initObservers(){
        viewModel.getCategoriesResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    displayCategories(result.data)
                }
                is AppResult.Error -> {
                    Logger.log(result.message)
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.editCategoryResult.observe(viewLifecycleOwner){ result ->
            when (result) {
                is AppResult.Success -> {
                    // Intentionally empty: a successful edit only updates the repository, no UI change needed
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.deleteCategoryResult.observe(viewLifecycleOwner){ result ->
            when (result) {
                is AppResult.Success -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.seasonLabel.observe(viewLifecycleOwner) { label ->
            binding.lblSeason.text = label
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        try{
            _binding = FragmentCategoriesBinding.inflate(inflater, container, false)
            initListeners()
            loadData()
        }catch(e: Exception){
            Logger.log(e)
        }

        return binding.root
    }

    private fun loadData() {
        binding.loadingPanel.visibility = View.VISIBLE
        binding.rviewCategories.visibility = View.GONE
        viewModel.getCategories()

        binding.loadingPanel.visibility = View.GONE
        binding.rviewCategories.visibility = View.VISIBLE
    }

    private fun displayCategories(categories: List<ExpenseCategory>) {
        val mutableCategories = categories.toMutableList()
        setupCategoriesAdapter(mutableCategories)
        attachSwipeCallback(mutableCategories, categories)
        loadTotal()
    }

    private fun setupCategoriesAdapter(mutableCategories: MutableList<ExpenseCategory>) {
        if (binding.rviewCategories.layoutManager == null || binding.rviewCategories.adapter == null) {
            binding.rviewCategories.layoutManager = LinearLayoutManager(requireContext())
            val adapter = RViewCategoriesAdapter(mutableCategories)
            adapter.setHasStableIds(true)
            binding.rviewCategories.adapter = adapter

        } else {
            (binding.rviewCategories.adapter as RViewCategoriesAdapter).updateData(mutableCategories)
        }
    }

    private fun attachSwipeCallback(mutableCategories: MutableList<ExpenseCategory>, categories: List<ExpenseCategory>) {
        val simpleCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition: Int = viewHolder.adapterPosition
                val toPosition: Int = target.adapterPosition
                Collections.swap(mutableCategories, fromPosition, toPosition)
                recyclerView.adapter?.notifyItemMoved(fromPosition, toPosition)

                Logger.log("${categories[fromPosition].name} moved from $fromPosition to $toPosition")

                updateCategoriesSortOrder(mutableCategories)

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val delCat = mutableCategories[viewHolder.adapterPosition]
                val backupPosition = viewHolder.adapterPosition
                (binding.rviewCategories.adapter as RViewCategoriesAdapter).removeItem(viewHolder.adapterPosition)
                if (isAdded) {
                    if(snackbar != null) { snackbar?.dismiss() }
                    snackbar = Snackbar.make(
                        binding.linearLayo,
                        "Category deleted",
                        Snackbar.LENGTH_LONG
                    )
                    snackbar!!.setAction("Undo") {
                        snackbar = null
                        (binding.rviewCategories.adapter as RViewCategoriesAdapter).addItem(viewHolder.adapterPosition, categories[backupPosition])

                    }

                    snackbar!!.addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) {
                                snackbar = null
                                deleteCategory(delCat.id)
                                if (_binding != null) loadTotal()
                            }
                        }
                    })

                    snackbar!!.show()
                }


            }

            override fun isItemViewSwipeEnabled(): Boolean {
                return true
            }

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                val swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
                return makeMovementFlags(dragFlags, swipeFlags)
            }
        }

        val itemTouchHelper = ItemTouchHelper(simpleCallback)
        itemTouchHelper.attachToRecyclerView(binding.rviewCategories)
    }

    @SuppressLint("DefaultLocale", "SetTextI18n")
    private fun loadTotal() {
        try {
            val adapter = binding.rviewCategories.adapter as RViewCategoriesAdapter
            val categories = adapter.list
            val totalSum = categories.sumOf { it.total }
            binding.lblTotal.text = String.format("%.2f", totalSum) + "€"
        } catch (ex: Exception) {
            Logger.log(ex)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCategoriesSortOrder(categories: List<ExpenseCategory>) {
        for (i in categories.indices) {
            val updatedCategory = categories[i]
            updatedCategory.setOrder(i)
            editCategory(updatedCategory)
        }
    }

    private fun editCategory(category: ExpenseCategory) {
        viewModel.editCategory(category)
    }


    private fun deleteCategory(categoryId: Long) {
        viewModel.deleteCategory(categoryId)
    }

    private fun initListeners() {
        binding.createCategory.setOnClickListener {
            findNavController().navigate(R.id.nav_createcategories)
        }
        binding.swiperefresh.setOnRefreshListener {
            snackbar?.dismiss()
            loadData()
            binding.swiperefresh.isRefreshing = false
        }
    }

    private fun showLogoutConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.ButtonsCustomColor)
            .setTitle("Log out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Yes") { dialog, _ ->
                (requireActivity() as MainActivity).doLogOut()
                dialog.dismiss()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
        _binding = null
    }
}
