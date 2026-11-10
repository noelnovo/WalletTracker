package win.downops.wallettracker.ui.categoriesExpenses

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import win.downops.wallettracker.R
import win.downops.wallettracker.databinding.FragmentCategoriesexpensesBinding
import win.downops.wallettracker.ui.adapters.RViewExpensesAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.models.ExpenseCategory
import win.downops.wallettracker.util.AppResultHandler
import win.downops.wallettracker.util.Logger
import win.downops.wallettracker.util.Messages.unexpectedError

@AndroidEntryPoint
class CategoriesExpensesFragment() : Fragment() {
    private val viewModel: CategoriesExpensesViewModel by viewModels()
    private var _binding: FragmentCategoriesexpensesBinding? = null
    private val binding get() = _binding!!
    private var snackbar: Snackbar? = null
    var categoryId: Long = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try{
            super.onViewCreated(view, savedInstanceState)
            initObservers()
        }catch(e: Exception){
            Logger.log(e)
        }
    }

    private fun initObservers(){
        viewModel.deleteResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.getCategoryResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    val category = result.data
                    if (category != null) {
                        binding.inputName.setText(category.name)
                        loadExpenses()
                    }
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.getExpensesByCategoryIdResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    displayExpenses(result.data)
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.editExpenseCategoryResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.nav_categories)
                }

                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.showAll.observe(viewLifecycleOwner) { isAll ->
            if (binding.toggleSeasonAll.isChecked != isAll) {
                binding.toggleSeasonAll.isChecked = isAll
            }
        }
        viewModel.deleteExpenseCategoryResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.nav_categories)
                }

                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        try{
            _binding = FragmentCategoriesexpensesBinding.inflate(inflater, container, false)

            val args : Bundle = requireArguments()
            categoryId = args.getLong("catId")
            initListeners()
            loadData()
        }catch(e: Exception){
            Logger.log(e)
        }

        return binding.root
    }




    private fun loadData() {
        binding.loadingPanel.visibility = View.VISIBLE
        binding.form.visibility = View.GONE


        viewModel.getCategory(categoryId)

        binding.loadingPanel.visibility = View.GONE
        binding.form.visibility = View.VISIBLE
    }

    private fun loadExpenses() {
        binding.loadingPanel.visibility = View.VISIBLE
        binding.form.visibility = View.GONE

        viewModel.getExpensesByCategoryId(categoryId)

        binding.loadingPanel.visibility = View.GONE
        binding.form.visibility = View.VISIBLE
    }


    private fun displayExpenses(list: List<Expense>) {
        val mutableExpenses = list.toMutableList()
        if(binding.rviewExpenses.layoutManager == null || binding.rviewExpenses.adapter == null){
            binding.rviewExpenses.layoutManager = LinearLayoutManager(requireContext() )
            binding.rviewExpenses.adapter = RViewExpensesAdapter(mutableExpenses)
        }else{
            (binding.rviewExpenses.adapter as RViewExpensesAdapter).updateData(mutableExpenses)
        }


        val simpleCallback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {

                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val delExpense = mutableExpenses[viewHolder.adapterPosition]
                val backupExpense = delExpense
                val backupPosition = viewHolder.adapterPosition

                (binding.rviewExpenses.adapter as RViewExpensesAdapter).removeItem(backupPosition)

                if (isAdded) {
                    snackbar = Snackbar.make(
                        binding.form,
                        "Expense deleted",
                        Snackbar.LENGTH_LONG
                    )

                    snackbar!!.setAction("Undo") {
                        (binding.rviewExpenses.adapter as RViewExpensesAdapter).addItem(backupPosition, backupExpense)
                    }

                    snackbar!!.addCallback(object : Snackbar.Callback() {
                        @RequiresApi(Build.VERSION_CODES.O)
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (event != Snackbar.Callback.DISMISS_EVENT_ACTION && isAdded) {
                                viewModel.deleteExpense(delExpense.id)
                            }
                        }
                    })

                    snackbar!!.show()
                }
            }

        }


        val itemTouchHelper = ItemTouchHelper(simpleCallback)
        itemTouchHelper.attachToRecyclerView(binding.rviewExpenses)
    }

    override fun onPause() {
        super.onPause()
        snackbar?.dismiss()
    }

    private fun initListeners() {
        binding.saveChanges.setOnClickListener {
            saveIfValid()
        }
        binding.inputName.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {

                saveIfValid()

                return@setOnEditorActionListener true
            }
            false
        }
        binding.addExpense.setOnClickListener{
            val bundle = Bundle()
            bundle.putLong("catId", categoryId)
            findNavController().navigate(R.id.nav_createexpense, bundle)
        }
        binding.toggleSeasonAll.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleShowAll(categoryId)
        }
        binding.delete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), R.style.ButtonsCustomColor)
                .setTitle("Delete Category")
                .setMessage("Are you sure you want to delete this category?")
                .setPositiveButton("Delete") { _, _ ->
                    deleteCategory()
                }
                .setNegativeButton("Cancel", null)
                .show()

        }

    }

    private fun saveIfValid() {
        val category = getCategory()
        val isValid = checkValidation(category)
        if (isValid){
            saveChanges()
        }
        else{
            Logger.log("Invalid data")
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkValidation(category: ExpenseCategory): Boolean {
        return category.name.isNotEmpty()
    }

    private fun saveChanges() {
        binding.loadingPanel.visibility = View.VISIBLE

        try {
            val category = getCategory()
            viewModel.editExpenseCategory(category)
        } catch (e: Exception) {
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        } finally {
            binding.loadingPanel.visibility = View.GONE
        }
    }

    private fun getCategory(): ExpenseCategory {
        val cat = ExpenseCategory(categoryId)
        cat.setName(binding.inputName.text.toString())
        return cat
    }

    private fun deleteCategory() {
        binding.loadingPanel.visibility = View.VISIBLE

        try {
            viewModel.deleteExpenseCategory(categoryId)
        } catch (e: Exception) {
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        } finally {
            binding.loadingPanel.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}