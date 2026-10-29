package hev.sockstun

/**
 * JNI-мост к нативной библиотеке heiher/sockstun libhev-socks5-tunnel.so.
 * Класс специально находится в пакете hev.sockstun, потому что JNI-символы
 * внутри .so называются Java_hev_sockstun_TProxyService_*.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /**
     * БЛОКИРУЮЩИЙ вызов — запускает event loop tun2socks в текущем потоке.
     * Возвращает управление только после вызова [TProxyStopService] из другого потока.
     * Всегда вызывать из отдельного рабочего потока.
     *
     * @param configPath абсолютный путь к YAML-файлу конфигурации.
     * @param fd TUN file descriptor из VpnService.Builder.establish() (через pfd.fd).
     */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    /** Сигнализирует запущенному циклу TProxyStartService о завершении. */
    @JvmStatic
    external fun TProxyStopService()

    /** Возвращает [uplinkBytes, downlinkBytes]. */
    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
