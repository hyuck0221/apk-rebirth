package com.hshim.apkrebirth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ApkRebirthApplication

fun main(args: Array<String>) {
    runApplication<ApkRebirthApplication>(*args)
}
