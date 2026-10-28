package app.dim.ui

import android.content.Context
import java.time.LocalTime
import kotlin.random.Random

/** Time-of-day greetings. One is picked per app open and never repeats the previous one for that part of the day. */
object Greetings {
    private val morning = listOf(   // 5 am - 12 pm
        "Good morning.",
        "Rise and shine, gently.",
        "Good morning. Water first?",
        "A fresh start today.",
        "Morning. How's your head?",
        "Hope you slept well.",
        "Easy does it this morning.",
        "Start slow, sip some water.",
        "Hydrated yet? A sip helps.",
        "Welcome back. Start calm.",
    )
    private val noon = listOf(      // 12 pm - 5 pm
        "Good afternoon.",
        "Hope your day is going well.",
        "Midday check-in.",
        "Had water and lunch yet?",
        "How's the head this afternoon?",
        "Take a short break.",
        "Halfway there, go easy.",
        "Pause and stretch a little.",
        "Be kind to yourself today.",
        "Stay hydrated, stay ahead.",
    )
    private val evening = listOf(   // 5 pm - 8 pm
        "Good evening.",
        "Time to wind down.",
        "Hope today was kind to you.",
        "Evening. How did today go?",
        "Ease into the evening.",
        "Dim the lights, slow down.",
        "Almost time to rest.",
        "Thanks for checking in today.",
        "Breathe in. Breathe out.",
        "Rest is progress too.",
    )
    private val night = listOf(     // 8 pm - 5 am
        "Good night.",
        "Rest well tonight.",
        "Up late? Be gentle.",
        "Time to rest your head.",
        "Quiet night, calm mind.",
        "Screens low, lights lower.",
        "Sleep helps. Rest up.",
        "Wishing you a peaceful night.",
        "Another day done. Well done.",
        "Breathe slow, let go.",
    )

    fun pick(ctx: Context, now: LocalTime = LocalTime.now()): String {
        val h = now.hour
        val (slot, list) = when {
            h in 5..11 -> "morning" to morning
            h in 12..16 -> "noon" to noon
            h in 17..19 -> "evening" to evening
            else -> "night" to night
        }
        val prefs = ctx.applicationContext.getSharedPreferences("dim_greeting", Context.MODE_PRIVATE)
        val last = prefs.getInt("last_$slot", -1)
        var i = Random.nextInt(list.size)
        if (i == last) i = (i + 1 + Random.nextInt(list.size - 1)) % list.size
        prefs.edit().putInt("last_$slot", i).apply()
        return list[i]
    }
}
