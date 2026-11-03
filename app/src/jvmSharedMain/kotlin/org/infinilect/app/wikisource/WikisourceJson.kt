// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.serialization.json.*

internal fun wikiJson(bytes:ByteArray):JsonObject {
    if(bytes.size>1048576)throw WikisourceInvalid()
    val text=try {bytes.decodeToString(throwOnInvalidSequence=true)}catch(_:Exception){throw WikisourceInvalid()}
    var depth=0;var quoted=false;var escaped=false;var tokens=0
    for(c in text) if(quoted) {if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
        else when(c) {'"'->quoted=true;'[','{'->{if(++depth>24 || ++tokens>10000)throw WikisourceInvalid()};']','}'->{if(--depth<0)throw WikisourceInvalid()};',',':'->{if(++tokens>10000)throw WikisourceInvalid()}}
    if(depth!=0 || quoted)throw WikisourceInvalid()
    // Reject duplicate/escaped-alias object keys before the tree parser can apply
    // last-value-wins semantics. Strings are bounded independently of the response.
    val objects=mutableListOf<MutableSet<String>?>();var i=0
    while(i<text.length) {
        when(text[i]) {
            '{','['->objects.add(if(text[i]=='{')mutableSetOf() else null)
            '}',']'->{if(objects.isEmpty())throw WikisourceInvalid();objects.removeAt(objects.lastIndex)}
            '"'->{
                val begin=i++;var escape=false
                while(i<text.length) {
                    if(i-begin>524288)throw WikisourceInvalid()
                    if(!escape && text[i]=='"')break
                    escape=!escape && text[i]=='\\';i++
                }
                if(i==text.length)throw WikisourceInvalid()
                var next=i+1;while(next<text.length && text[next] in " \t\r\n")next++
                if(next<text.length && text[next]==':') {
                    val key=try {(Json.parseToJsonElement(text.substring(begin,i+1)) as JsonPrimitive).content}catch(_:Exception){throw WikisourceInvalid()}
                    val keys=objects.lastOrNull() ?: throw WikisourceInvalid()
                    if(!keys.add(key) || keys.size>64)throw WikisourceInvalid()
                }
            }
        }
        i++
    }
    if(objects.isNotEmpty())throw WikisourceInvalid()
    val root=try {Json.parseToJsonElement(text) as? JsonObject}catch(_:Exception){null} ?: throw WikisourceInvalid()
    root["error"]?.let {error ->
        val code=(error as? JsonObject)?.get("code").let {it as? JsonPrimitive}?.contentOrNull
        if(code in setOf("maxlag","ratelimited","cirrussearch-too-busy-error"))throw WikisourceApiBusy()
        throw WikisourceInvalid()
    }
    return root
}
