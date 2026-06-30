package com.caros.ui.ecosystem

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentEcosystemBinding
import com.caros.termux.ServiceStatus
import com.caros.termux.TermuxServiceMonitor
import com.caros.termux.TermuxSetupManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EcosystemFragment : Fragment() {
    private var _binding: FragmentEcosystemBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var setupManager: TermuxSetupManager
    @Inject lateinit var serviceMonitor: TermuxServiceMonitor

    // Wizard step 0=check, 1=install, 2=permissions, 3=setup, 4=done
    private var wizardStep = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentEcosystemBinding.inflate(i, c, false); return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!setupManager.isTermuxInstalled) {
            showWizard()
        } else {
            showDashboard()
        }
    }

    private fun showWizard() {
        binding.wizardLayout.visibility = View.VISIBLE
        binding.dashboardLayout.visibility = View.GONE
        binding.tvWizardTitle.text = "Nastavení ekosystému"
        binding.tvWizardDesc.text = "CarOS používá Termux pro pokročilé funkce.\nNainstaluj ho z F-Droid."
        binding.btnWizardAction.text = "Nainstalovat Termux (F-Droid)"
        binding.btnWizardAction.setOnClickListener {
            when (wizardStep) {
                0 -> { setupManager.openTermuxFDroid(); advanceWizard() }
                1 -> { setupManager.grantTermuxPermission(); advanceWizard() }
                2 -> runSetup()
                else -> showDashboard()
            }
        }
    }

    private fun advanceWizard() {
        wizardStep++
        val (title, desc, btn) = when (wizardStep) {
            1 -> Triple("Termux nainstalován?", "Klikni po dokončení instalace z F-Droid.", "Mám nainstalováno →")
            2 -> Triple("Oprávnění", "Povolíme CarOS spouštět příkazy v Termuxu.", "Udělit oprávnění →")
            3 -> Triple("Instalace služeb", "Nainstalujeme Python, MQTT, InfluxDB, SSH.", "Spustit instalaci →")
            else -> Triple("Hotovo!", "Vše je připraveno.", "Zobrazit dashboard")
        }
        binding.tvWizardTitle.text = title
        binding.tvWizardDesc.text = desc
        binding.btnWizardAction.text = btn
    }

    private fun runSetup() {
        binding.setupProgressBar.visibility = View.VISIBLE
        binding.btnWizardAction.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            setupManager.runSetupScript().collectLatest { status ->
                binding.tvSetupStatus.text = status.removePrefix("STATUS:").removePrefix("DONE:")
                if (status.startsWith("DONE:") || status.startsWith("ERROR:")) {
                    binding.setupProgressBar.visibility = View.GONE
                    binding.btnWizardAction.isEnabled = true
                    if (status.startsWith("DONE:")) { wizardStep = 4; advanceWizard() }
                }
            }
        }
    }

    private fun showDashboard() {
        binding.wizardLayout.visibility = View.GONE
        binding.dashboardLayout.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            serviceMonitor.statusFlow.collectLatest { status ->
                updateDashboard(status)
            }
        }
    }

    private fun updateDashboard(s: ServiceStatus) {
        val ip = serviceMonitor.getLocalIP()
        val sshCmd = "ssh user@$ip -p 8022"
        binding.tvSSHStatus.text = if (s.ssh) "● Aktivní" else "○ Neaktivní"
        binding.tvSSHAddress.text = "$ip:8022"
        binding.tvSSHCommand.text = sshCmd
        binding.btnCopySSH.setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SSH", sshCmd))
            android.widget.Toast.makeText(context, "Zkopírováno: $sshCmd", android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.tvMQTTStatus.text = if (s.mqtt) "● Aktivní | ${s.mqttClients} klientů" else "○ Neaktivní"
        binding.tvInfluxStatus.text = if (s.influx) "● Nahrává" else "○ Zastaveno"
        binding.tvPythonStatus.text = if (s.pythonBridge) "● Aktivní" else "○ Zastaveno"
        binding.tvSyncStatus.text = if (s.syncthing) "● Aktivní" else "○ Čeká"
        binding.tvGrafanaAddress.text = "$ip:3000"

        binding.btnRestartMQTT.setOnClickListener { serviceMonitor.restartService("mqtt") }
        binding.btnRestartPython.setOnClickListener { serviceMonitor.restartService("python") }
        binding.btnOpenGrafana.setOnClickListener {
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://$ip:3000")))
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
