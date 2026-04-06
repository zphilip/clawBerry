package clawberry.aiworm.cn.ui

import androidx.compose.runtime.Composable
import clawberry.aiworm.cn.MainViewModel
import clawberry.aiworm.cn.ui.chat.ChatSheetContent

@Composable
fun ChatSheet(viewModel: MainViewModel) {
  ChatSheetContent(viewModel = viewModel)
}
