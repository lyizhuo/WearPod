package com.example.wearpod.presentation

enum class SleepTimerMode(val minutes: Int) {
    Off(0),
    In15Minutes(15),
    In30Minutes(30),
    In45Minutes(45),
    InOneHour(60),
    EndOfEpisode(-1),
}