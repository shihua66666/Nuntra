package com.shihua66666.nuntra.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全应用唯一的 DataStore 实例。
 *
 * 为什么只用一个：
 *  · Preferences DataStore 要求同一文件只被一份实例持有，多个实例会抛
 *    IllegalStateException（There are multiple DataStores active for the same file）。
 *  · 标签、关注人、设置、迁移 flag 全部集中在这一个文件里，
 *    顺序化写入天然一致，且迁移只需要一个 flag。
 */
val Context.nuntraDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuntra_prefs")
