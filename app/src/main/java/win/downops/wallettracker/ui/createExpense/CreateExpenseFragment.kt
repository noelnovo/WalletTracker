package win.downops.wallettracker.ui.createExpense

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Spinner
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import win.downops.wallettracker.R
import win.downops.wallettracker.databinding.FragmentCreateexpenseBinding
import win.downops.wallettracker.ui.adapters.ComboCategoriasAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.models.ExpenseCategory
import win.downops.wallettracker.util.AppResultHandler
import win.downops.wallettracker.util.Logger
import win.downops.wallettracker.util.Messages.unexpectedError
import java.sql.Date
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val DATE_PATTERN = "yyyy-MM-dd"

@AndroidEntryPoint
class CreateExpenseFragment : Fragment() {
    private val viewModel: CreateExpenseViewModel by viewModels()
    var isFromCatForm: Boolean = false
    var expenseId: Long = 0
    var categoryId: Long = 0

    private var _binding: FragmentCreateexpenseBinding? = null

    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try{
            super.onViewCreated(view, savedInstanceState)
            initObservers()
        }catch(e: Exception){
            Logger.log(e)
        }
    }

    private fun initObservers(){
        viewModel.getExpenseResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    val expense = result.data
                    loadExpense(expense)
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.getCategoriesResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    val categories = result.data
                    loadSpinner(categories)
                    selectCategory(categoryId)
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.deleteExpenseResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    findNavController().popBackStack()
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.editExpenseResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    findNavController().popBackStack()
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
        viewModel.createExpenseResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    findNavController().popBackStack()
                }
                is AppResult.Error -> {
                    AppResultHandler.handleError(requireContext(), result)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        try{
            _binding = FragmentCreateexpenseBinding.inflate(inflater, container, false)

            val args : Bundle? = arguments
            if(args != null){
                categoryId = args.getLong("catId")
                isFromCatForm = categoryId > 0

                expenseId = args.getLong("expenseId")
            }

            initListeners()
            loadData(expenseId)

            binding.root.post {
                val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                imm?.showSoftInput(binding.inputPrice, InputMethodManager.SHOW_IMPLICIT)
            }

            if (expenseId <= 0)
                binding.inputPrice.requestFocus()

        }catch(e: Exception){
            Logger.log(e)
        }


        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadData(expenseId: Long) {
        binding.inputDate.isEnabled = false
        loadDefaultDateTime()
        viewModel.getCategories()

        if (expenseId > 0) {
            viewModel.getExpense(expenseId)
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun loadExpense(expense: Expense?) {
        try {
            if (expense != null){
                binding.inputPrice.setText(expense.getPrice().toString())

                binding.inputDesc.setText(expense.description)
                val format = SimpleDateFormat(DATE_PATTERN)
                val stringDate = format.format(expense.getDate())
                binding.inputDate.setText(stringDate)

            }
        }
        catch (e: Exception){
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSpinner(categories: List<ExpenseCategory>) {
        binding.comboCategorias
        val spinner: Spinner = binding.comboCategorias

        val adapter = ComboCategoriasAdapter(
            requireContext(),
            categories
        )
        spinner.adapter = adapter
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadDefaultDateTime() {

        val currentDateTime = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern(DATE_PATTERN)
        binding.inputDate.setText(formatter.format(currentDateTime))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initListeners() {
        binding.pickDate.setOnClickListener {
            showDatePickerDialog()
        }
        binding.inputPrice.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_NEXT ||
                (actionId == EditorInfo.IME_ACTION_DONE)) {
                createOrEditChanges()
                return@setOnEditorActionListener true
            }
            false
        }
        binding.createExpense.setOnClickListener {
            createOrEditChanges()
        }
        if (expenseId > 0){
            binding.delete.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext(), R.style.ButtonsCustomColor)
                    .setTitle("Delete Expense")
                    .setMessage("Are you sure you want to delete this expense?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            delete(expenseId)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            }
        }
        else{
            binding.delete.visibility = View.GONE
        }


    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createOrEditChanges() {
        try{
            val expense = getExpense()
            val isValid = checkValidation(expense)
            if(isValid){
                if(expenseId > 0){
                    edit()
                }else{
                    create()
                }
            }else{
                Logger.log("Invalid data")
                Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
            }
        }catch(e: Exception){
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkValidation(expense: Expense): Boolean {
        if(expense.getPrice() <= 0){
            return false
        }

        return true
    }

    private fun delete(expenseId: Long) {
        try {
            viewModel.deleteExpense(expenseId)
        } catch (e: Exception) {
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun edit() {
        try {
            val expense = getExpense()
            viewModel.editExpense(expense)

        } catch (e: Exception) {
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun create() {
        try {
            val expense = getExpense()
            viewModel.createExpense(expense)
        } catch (e: Exception) {
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    private fun selectCategory(categoryId: Long) {
        try{
            if(categoryId > 0){
                val adapter = binding.comboCategorias.adapter as ComboCategoriasAdapter
                val pos = adapter.getById(categoryId)
                if(pos >= 0)
                    binding.comboCategorias.setSelection(pos)
            }

        }catch (e: Exception){
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun getExpense(): Expense {
        try {
            val priceString = binding.inputPrice.text.toString()
            var price = 0.0
            if (priceString.isNotEmpty()){
                price = priceString.toDouble()
            }

            val descString = binding.inputDesc.text.toString()

            val dateString = binding.inputDate.text.toString()

            val formatter = DateTimeFormatter.ofPattern(DATE_PATTERN)
            var date = Date.valueOf(formatter.format(LocalDate.now()))


            if (dateString.isNotEmpty()){
                date = Date.valueOf(dateString)
            }

            val adapter = binding.comboCategorias.adapter as ComboCategoriasAdapter
            val category = adapter.getItem(binding.comboCategorias.selectedItemPosition)
            val catId: Long = category.id
            val expense = if (expenseId > 0){
                Expense(expenseId, price, date, catId, descString)
            }
            else{
                Expense(price, date, catId, descString)
            }
            return expense

        }
        catch (e:Exception){
            throw e
        }

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showDatePickerDialog() {
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, day ->

            val selectedDate = LocalDate.of(year, month + 1, day)
            val formatter = DateTimeFormatter.ofPattern(DATE_PATTERN)
            binding.inputDate.setText(formatter.format(selectedDate))
        }

        val currDate = LocalDate.now()
        val datePicker = DatePickerDialog(
            requireContext(),
            dateSetListener,
            currDate.year,
            currDate.monthValue - 1,
            currDate.dayOfMonth
        )
        datePicker.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}