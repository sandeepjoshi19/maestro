package maestro.web.selenium

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import java.util.logging.Level
import java.util.logging.Logger

class ChromeSeleniumFactory(
    private val isHeadless: Boolean
) : SeleniumFactory {

    override fun create(): WebDriver {
        System.setProperty("webdriver.chrome.silentOutput", "true")
        System.setProperty(ChromeDriverService.CHROME_DRIVER_SILENT_OUTPUT_PROPERTY, "true")
        Logger.getLogger("org.openqa.selenium").level = Level.OFF
        Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder").level = Level.OFF

        val driverService = ChromeDriverService.Builder()
            .withLogLevel(ChromiumDriverLogLevel.OFF)
            .build()

        return ChromeDriver(
            driverService,
            ChromeOptions().apply {
                addArguments("--no-sandbox")
                addArguments("--disable-setuid-sandbox")
                addArguments("--disable-dev-shm-usage")
                addArguments("--disable-gpu")
                addArguments("--disable-software-rasterizer")
                addArguments("--disable-dbus") 
                addArguments("--use-angle=swiftshader") 
                addArguments("--remote-allow-origins=*")
                addArguments("--lang=en")
                addArguments("--disable-extensions")
                addArguments("--mute-audio")
                if (isHeadless) {
                    addArguments("--headless=new")
                    addArguments("--window-size=2560,1440")
                    setExperimentalOption("detach", true)
                }
            }
        )
    }

}