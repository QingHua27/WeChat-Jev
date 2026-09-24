package com.jev.relationship.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFFF2F6FD),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, Color(0xFFDCE6F5)),
        content = content,
    )
}

@Composable
internal fun SettingsDropdownItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = Modifier
            .heightIn(min = 38.dp, max = 38.dp)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFE3EEFF) else Color.Transparent),
        text = {
            Text(
                text = label,
                color = Color(0xFF17233C),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            if (selected) {
                Text("✓", color = Color(0xFF1677E8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Spacer(Modifier.size(16.dp))
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = androidx.compose.material3.MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
