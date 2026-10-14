package com.kherio.gitwatch.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gitwatch", Context.MODE_PRIVATE)

    /** Token opcional de GitHub (solo lectura de repos públicos). Sube el límite de 60 a 5000 peticiones/hora. */
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var intervalHours: Int
        get() = sp.getInt("interval", 6)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** El usuario ha activado el uso de root (módulos + instalación silenciosa). Evita pedir su sin avisar. */
    var rootEnabled: Boolean
        get() = sp.getBoolean("root", false)
        set(v) = sp.edit().putBoolean("root", v).apply()

    /** Instalar solo (con root) las actualizaciones de APPS en segundo plano. Los módulos nunca se instalan solos. */
    var autoInstall: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var notify: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    var defaultPrerelease: Boolean
        get() = sp.getBoolean("prerelease", false)
        set(v) = sp.edit().putBoolean("prerelease", v).apply()
}
