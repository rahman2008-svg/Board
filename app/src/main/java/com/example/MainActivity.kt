package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.database.BoardDatabase
import com.example.data.database.BoardRepository
import com.example.ui.DashboardScreen
import com.example.ui.WhiteboardScreen
import com.example.ui.WhiteboardViewModel
import com.example.ui.WhiteboardViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize local Room database and repository
        val database = BoardDatabase.getDatabase(applicationContext)
        val repository = BoardRepository(database.boardDao())
        
        // Instantiate the centralized WhiteboardViewModel
        val viewModel = ViewModelProvider(
            this,
            WhiteboardViewModelFactory(application, repository)
        )[WhiteboardViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard"
                    ) {
                        // Dashboard view
                        composable("dashboard") {
                            DashboardScreen(
                                viewModel = viewModel,
                                onOpenBoard = { boardId ->
                                    navController.navigate("whiteboard/$boardId")
                                }
                            )
                        }
                        
                        // Whiteboard drawing view
                        composable(
                            route = "whiteboard/{boardId}",
                            arguments = listOf(
                                navArgument("boardId") { type = NavType.LongType }
                            )
                        ) { backStackEntry ->
                            val boardId = backStackEntry.arguments?.getLong("boardId") ?: 0L
                            
                            // Load the correct whiteboard into VM state
                            LaunchedEffect(boardId) {
                                viewModel.openBoard(boardId)
                            }
                            
                            WhiteboardScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
