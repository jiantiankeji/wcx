package com.Johnny.wcx.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi

/**
 * 多选群聊选择器。
 *
 * 「AI 自动回复」与「服务器通知转发」共用同一套选择界面，传入初始已选群即可。
 *
 * @param description 列表顶部说明文字，如"选择需要接收通知的群聊"
 * @param initialSelected 初始已选中的群 ID 集合
 * @param onSave 点击保存时回调，参数为最终选中的群 ID 集合
 */
@Composable
fun GroupSelectorScreen(
    description: String,
    initialSelected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val groups = remember {
        WeDatabaseApi.getGroups().filter { it.wxId.isNotBlank() }
    }
    val selected = remember { initialSelected.toMutableSet() }
    val listState = rememberLazyListState()

    AlertDialogContent(
        title = { Text("选择群聊") },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(groups, key = { it.wxId }) { group ->
                    val isSelected = remember { mutableStateOf(selected.contains(group.wxId)) }
                    ListItem(
                        modifier = Modifier.clickable {
                            isSelected.value = !isSelected.value
                            if (isSelected.value) {
                                selected.add(group.wxId)
                            } else {
                                selected.remove(group.wxId)
                            }
                        },
                        headlineContent = { Text(group.displayName) },
                        supportingContent = { Text(group.wxId) },
                        trailingContent = {
                            Text(if (isSelected.value) "✓" else "")
                        }
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                onSave(selected.toSet())
                onDismiss()
            }) { Text("保存") }
        }
    )
}