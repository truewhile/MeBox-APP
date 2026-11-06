package app.mebox.android.feature.cache

import app.mebox.android.core.reader.ReaderBookStorage

data class ReaderStorageUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val books: List<ReaderBookStorage> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
)
