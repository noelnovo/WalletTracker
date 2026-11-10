package win.downops.wallettracker.ui.metrics

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import win.downops.wallettracker.R
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.databinding.FragmentMetricsBinding
import java.util.Locale

@AndroidEntryPoint
class MetricsFragment : Fragment() {

    private val viewModel: MetricsViewModel by viewModels()
    private var _binding: FragmentMetricsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSpinners()
        observeViewModel()

        viewModel.loadMetrics()
    }

    private fun setupSpinners() {
        binding.spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedItem = parent.getItemAtPosition(position)
                if (selectedItem is CategoryOption) {
                    val selectedCatId = if (selectedItem.id == -1L) null else selectedItem.id
                    viewModel.setFilter(selectedCatId, viewModel.selectedMonthKey)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                // Intentionally empty: keeping the current category filter when nothing is picked
            }
        }

        binding.spinnerMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedItem = parent.getItemAtPosition(position)
                if (selectedItem is MonthOption) {
                    val selectedMonthKey = if (selectedItem.key == "") null else selectedItem.key
                    viewModel.setFilter(viewModel.selectedCategoryId, selectedMonthKey)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                // Intentionally empty: keeping the current month filter when nothing is picked
            }
        }
    }

    private fun observeViewModel() {
        viewModel.categoryOptions.observe(viewLifecycleOwner) { options ->
            val list = mutableListOf(CategoryOption(-1, "All Categories")) + options
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, list)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCategory.adapter = adapter
        }

        viewModel.monthOptions.observe(viewLifecycleOwner) { options ->
            val list = mutableListOf(MonthOption("", "All Months")) + options
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, list)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerMonth.adapter = adapter
        }

        viewModel.metricsResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AppResult.Success -> {
                    showData(result.data)
                }
                is AppResult.Error -> {
                    showEmpty(result.message)
                }
            }
        }
    }

    private fun showData(data: MetricsData) {
        binding.loadingPanel.visibility = View.GONE
        binding.lblEmpty.visibility = View.GONE
        binding.filterRow.visibility = View.VISIBLE
        binding.scrollView.visibility = View.VISIBLE

        binding.lblTotal.text = String.format(Locale.getDefault(), "%.2f€", data.total)
        binding.lblCount.text = data.count.toString()
        binding.lblAverage.text = String.format(Locale.getDefault(), "%.2f€", data.average)

        binding.categoryContainer.removeAllViews()
        data.categories.forEach { metric ->
            val view = createMetricItem(metric.name, metric.total, metric.percentage)
            binding.categoryContainer.addView(view)
        }

        binding.monthlyContainer.removeAllViews()
        data.months.forEach { metric ->
            val view = createMetricItem(metric.label, metric.total, metric.percentage)
            binding.monthlyContainer.addView(view)
        }

        // Handle Comparison section
        binding.comparisonSection.visibility = if (data.comparison != null) View.VISIBLE else View.GONE
        data.comparison?.let { comp ->
            binding.lblCompExpenses.text = String.format(Locale.getDefault(), "%.2f€", comp.expenseTotal)
            binding.lblCompImports.text = String.format(Locale.getDefault(), "%.2f€", comp.importTotalExpense)
            binding.lblCompDiff.text = String.format(Locale.getDefault(), "%.2f€", comp.difference)
            binding.lblCompMatch.text = String.format(Locale.getDefault(), "%.1f%%", comp.matchPercentage)
            
            val colorRes = when {
                comp.matchPercentage >= 95 -> R.color.purple_500
                comp.matchPercentage >= 80 -> android.R.color.holo_orange_dark
                else -> R.color.red
            }
            binding.lblCompMatch.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }
    }

    private fun createMetricItem(label: String, value: Double, percentage: Double): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        header.addView(TextView(requireContext()).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        header.addView(TextView(requireContext()).apply {
            text = String.format(Locale.getDefault(), "%.2f€ (%.1f%%)", value, percentage)
            setTypeface(null, Typeface.BOLD)
        })

        layout.addView(header)

        val progressBackground = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8
            ).apply { setMargins(0, 8, 0, 0) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_gray))
        }

        val progressForeground = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                8
            ).apply { setMargins(0, -8, 0, 0) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.purple_500))
            post {
                val params = layoutParams as LinearLayout.LayoutParams
                params.width = (width * (percentage / 100.0)).toInt()
                layoutParams = params
            }
        }

        layout.addView(progressBackground)
        layout.addView(progressForeground)

        return layout
    }

    private fun showEmpty(message: String) {
        binding.loadingPanel.visibility = View.GONE
        binding.scrollView.visibility = View.GONE
        binding.lblEmpty.visibility = View.VISIBLE
        binding.lblEmpty.text = message
        binding.filterRow.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
