package win.downops.wallettracker.ui.createCategories

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import win.downops.wallettracker.R
import win.downops.wallettracker.databinding.FragmentCreatecategoriesBinding
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.ExpenseCategory
import win.downops.wallettracker.util.AppResultHandler
import win.downops.wallettracker.util.Logger
import win.downops.wallettracker.util.Messages.unexpectedError

@AndroidEntryPoint
class CreateCategoriesFragment : Fragment() {
    private val viewModel: CreateCategoriesViewModel by viewModels()
    private var _binding: FragmentCreatecategoriesBinding? = null
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
        viewModel.createCategoryResult.observe(viewLifecycleOwner){ result ->
            when (result) {
                is AppResult.Success -> {
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

            _binding = FragmentCreatecategoriesBinding.inflate(inflater, container, false)

            initListeners()

            binding.inputName.requestFocus()
            binding.root.post {
                val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                imm?.showSoftInput(binding.inputName, InputMethodManager.SHOW_IMPLICIT)
            }

        }catch(e: Exception){
            Logger.log(e)
        }

        return binding.root

    }

    private fun initListeners() {
        binding.createCategory.setOnClickListener {
            saveChanges()
        }
        binding.inputName.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {

                saveChanges()

                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun saveChanges() {
        val category = getCategory()
        val isValid = checkValidation(category)
        if(isValid){
            save()

        }else{
            Logger.log("Category fields are invalid")
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()

        }
    }

    private fun checkValidation(category: ExpenseCategory): Boolean {
        return category.name.isNotEmpty()
    }

    private fun save() {
        try {
            val category = getCategory()
            viewModel.createCategory(category)
        } catch (e: Exception) {
            Logger.log(e)
            Toast.makeText(requireContext(), unexpectedError, Toast.LENGTH_LONG).show()
        }
    }

    private fun getCategory(): ExpenseCategory {
        return ExpenseCategory(binding.inputName.text.toString())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}