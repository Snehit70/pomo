package com.pomo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialFadeThrough
import com.pomo.MainActivity
import com.pomo.crew.CrewRepository
import com.pomo.ui.screens.CrewScreen
import com.pomo.ui.screens.CrewScreenState
import com.pomo.ui.theme.PomoTheme
import com.pomo.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

public class CrewFragment : Fragment() {
    private val screenState = MutableStateFlow(CrewScreenState(isLoading = true))
    private lateinit var repository: CrewRepository
    private var liveBoardJob: Job? = null

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        repository = CrewRepository(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PomoTheme(mode = mainActivity?.prefs?.themeMode ?: ThemeMode.System) {
                val currentState by screenState.collectAsState()
                CrewScreen(
                    state = currentState,
                    onCreateCrew = { displayName -> createCrew(displayName) },
                    onJoinCrew = { joinCode, displayName -> joinCrew(joinCode, displayName) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBoard()
        startLiveBoard()
    }

    override fun onPause() {
        liveBoardJob?.cancel()
        liveBoardJob = null
        super.onPause()
    }

    private fun refreshBoard() {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            screenState.value = CrewScreenState(
                isLoading = false,
                board = repository.currentBoard(),
            )
        }
    }

    private fun createCrew(displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            screenState.value = CrewScreenState(
                isLoading = false,
                board = repository.createSoloCrew(displayName),
            )
            startLiveBoard()
        }
    }

    private fun joinCrew(joinCode: String, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            val board = repository.joinCrew(joinCode, displayName)
            screenState.value = CrewScreenState(
                isLoading = false,
                board = board,
                errorMessage = if (board == null) "Invalid join code" else null,
            )
            if (board != null) {
                startLiveBoard()
            }
        }
    }

    private fun startLiveBoard() {
        liveBoardJob?.cancel()
        liveBoardJob = viewLifecycleOwner.lifecycleScope.launch {
            repository.observeCurrentBoard().collect { board ->
                screenState.value = CrewScreenState(
                    isLoading = false,
                    board = board,
                )
            }
        }
    }
}
