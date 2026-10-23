package com.focuslock.app

import android.service.notification.NotificationListenerService

/**
 * Exists ONLY so FocusLock shows up in Settings → Do Not Disturb access.
 *
 * Android builds that list from the apps that declare a
 * NotificationListenerService; FocusLock declared none, so it was invisible
 * in the list and Tamim could not grant the notification-policy access the
 * silence-during-lock feature needs (his v1.4.0 screenshot: every other app
 * listed, FocusLock nowhere).
 *
 * This service reads nothing and posts nothing. The user only has to toggle
 * FocusLock on in the "Do Not Disturb access" list — notification access
 * itself never needs enabling for DND control to work.
 */
class DndAccessService : NotificationListenerService()
