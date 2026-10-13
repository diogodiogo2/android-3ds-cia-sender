package br.local.ciasender;

import java.util.Locale;

/**
 * All user-visible texts. English is the default; Portuguese is used when the
 * device language is Portuguese. To add a language, add a field to each constant
 * and extend {@link #get(Object...)}.
 *
 * Texts may contain String.format placeholders (%s, %d).
 */
public enum Msg {
    DEVICE_IP("This device's IP: %s", "IP deste dispositivo: %s"),
    NO_WIFI("No Wi-Fi: connect this device to the same network as the 3DS.",
            "Sem Wi-Fi: conecte este dispositivo na mesma rede do 3DS."),
    LABEL_3DS_IP("3DS IP:", "IP do 3DS:"),
    MODE_FILE("Select file", "Selecionar arquivo"),
    MODE_FOLDER("Select folder", "Selecionar pasta"),
    HINT_FILE_PATH("File path", "Caminho do arquivo"),
    HINT_FOLDER_PATH("Folder path", "Caminho da pasta"),
    BROWSE("Browse", "Procurar"),
    REFRESH("Refresh list", "Atualizar lista"),
    SEND("Send to 3DS", "Enviar para o 3DS"),
    FILES_FOUND("%d .cia file(s) found.", "%d arquivo(s) .cia encontrado(s)."),
    FOLDER_UNREADABLE("Could not read the folder (check the path and the storage permission).",
            "Não consegui ler a pasta (confira o caminho e a permissão de armazenamento)."),
    ENTER_3DS_IP("Enter the 3DS IP address.", "Digite o IP do 3DS."),
    INVALID_IP("Invalid IP address. Use the format 192.168.0.50.",
            "IP inválido. Use o formato 192.168.0.50."),
    SELECT_A_FILE("Select a file.", "Selecione um arquivo."),
    FILE_NOT_FOUND("File not found or not readable: %s", "Arquivo não encontrado ou ilegível: %s"),
    SIZE_UNKNOWN("Could not determine the file size.", "Não consegui descobrir o tamanho do arquivo."),
    CHECK_AT_LEAST_ONE("Check at least one file.", "Marque ao menos um arquivo."),
    SENDING("Sending list to %s...", "Enviando lista para %s..."),
    SENT("Sent (%d). The 3DS will start downloading now; keep this app open.",
            "Enviado (%d). O 3DS vai baixar agora; mantenha este app aberto."),
    SEND_FAILED("Could not reach the 3DS: %s (is FBI on \"Receive URLs over the network\"?)",
            "Falha ao falar com o 3DS: %s (o FBI está em \"Receive URLs over the network\"?)"),
    SERVED("3DS downloaded: %s", "3DS baixou: %s"),
    INTERRUPTED("Connection interrupted: %s", "Conexão interrompida: %s"),
    PORT_FAILED("Could not open port %d: %s", "Não consegui abrir a porta %d: %s"),
    NO_PICKER("No file picker available on this device. Type the path instead.",
            "Nenhum seletor de arquivos disponível. Digite o caminho.");

    private final String en;
    private final String pt;

    Msg(String en, String pt) {
        this.en = en;
        this.pt = pt;
    }

    /** True when the current default locale is Portuguese (any region). */
    static boolean portuguese() {
        return "pt".equals(Locale.getDefault().getLanguage());
    }

    /** Returns the text in the device language, formatted with the given arguments. */
    public String get(Object... args) {
        String text = portuguese() ? pt : en;
        return args.length == 0 ? text : String.format(Locale.US, text, args);
    }

    /** Raw texts, used by tests. */
    String raw(boolean portuguese) {
        return portuguese ? pt : en;
    }
}
