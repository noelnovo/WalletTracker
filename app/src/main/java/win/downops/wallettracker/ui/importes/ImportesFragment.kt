package win.downops.wallettracker.ui.importes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import win.downops.wallettracker.R
import win.downops.wallettracker.databinding.FragmentImportesBinding
import java.util.Locale

@AndroidEntryPoint
class ImportesFragment : Fragment() {

    private var _binding: FragmentImportesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ImportesViewModel by viewModels()
    private val adapter = RViewImportesAdapter()

    private var suppressYearListener = false
    private var suppressMonthListener = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImportesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rviewImportes.layoutManager = LinearLayoutManager(requireContext())
        binding.rviewImportes.adapter = adapter

        binding.swiperefresh.setOnRefreshListener {
            viewModel.loadSeasons()
        }

        binding.spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (suppressYearListener) return
                val year = (parent.getItemAtPosition(pos) as YearOption).year
                viewModel.selectYear(year)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.spinnerMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (suppressMonthListener) return
                val monthOpt = parent.getItemAtPosition(pos) as MonthOption
                viewModel.loadImportes(monthOpt.season.getId())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.btnEmptySeason.setOnClickListener {
            val selectedSeasonId = viewModel.selectedMonthId.value ?: return@setOnClickListener

            MaterialAlertDialogBuilder(requireContext(), R.style.ButtonsCustomColor)
                .setTitle("Vaciar mes")
                .setMessage("¿Estás seguro de que quieres eliminar todos los importes de este mes?")
                .setPositiveButton("Eliminar") { _, _ ->
                    viewModel.emptySeason(selectedSeasonId)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        viewModel.years.observe(viewLifecycleOwner) { yearList ->
            suppressYearListener = true
            val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, yearList)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerYear.adapter = spinnerAdapter
            suppressYearListener = false
        }

        viewModel.months.observe(viewLifecycleOwner) { monthList ->
            suppressMonthListener = true
            val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, monthList)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerMonth.adapter = spinnerAdapter
            
            viewModel.selectedMonthId.value?.let { monthId ->
                val index = monthList.indexOfFirst { it.season.getId() == monthId }
                if (index >= 0) {
                    binding.spinnerMonth.setSelection(index)
                }
            }
            suppressMonthListener = false
        }

        viewModel.selectedMonthId.observe(viewLifecycleOwner) { monthId ->
            val monthList = viewModel.months.value ?: return@observe
            val index = monthList.indexOfFirst { it.season.getId() == monthId }
            if (index >= 0 && binding.spinnerMonth.selectedItemPosition != index) {
                suppressMonthListener = true
                binding.spinnerMonth.setSelection(index)
                suppressMonthListener = false
            }
        }

        viewModel.importes.observe(viewLifecycleOwner) { list ->
            adapter.setData(list)
            binding.swiperefresh.isRefreshing = false
        }

        viewModel.total.observe(viewLifecycleOwner) { total ->
            binding.lblTotal.text = String.format(Locale.getDefault(), "%.2f€", total)
        }
        viewModel.totalIncome.observe(viewLifecycleOwner) { income ->
            binding.lblTotalIncome.text = String.format(Locale.getDefault(), "%.2f€", income)
        }
        viewModel.totalExpense.observe(viewLifecycleOwner) { expense ->
            binding.lblTotalExpense.text = String.format(Locale.getDefault(), "%.2f€", expense)
        }

        viewModel.loadSeasons()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
