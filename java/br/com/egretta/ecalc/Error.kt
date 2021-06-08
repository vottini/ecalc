package br.com.egretta.ecalc

import android.content.Context

enum class Error {
    NONE,
    ARITHMETIC,
    OFFLIMITS,
    DOMAIN
}

fun getErrorString(ctx : Context, err : Error) : String {
    when {
        (Error.ARITHMETIC == err) -> return ctx.getString(R.string.error_arithmetic)
        (Error.OFFLIMITS == err) -> return ctx.getString(R.string.error_too_big)
        (Error.DOMAIN == err) -> return ctx.getString(R.string.error_domain)
    }

    return ""
}
