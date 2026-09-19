package dev.mopiux.atmosia.core;

/**
 * Que nubes se dibujan. Es el interruptor principal del mod.
 *
 * Son tres estados y no un booleano porque "apagar Atmosia" es ambiguo: puede querer decir
 * "devolveme las de siempre" o "no quiero ninguna nube". Con un solo booleano el jugador no puede
 * pedir la segunda, y nosotros no podemos distinguir un fallo de supresion de un cielo vacio.
 *
 * El modo NINGUNA ademas sirve de diagnostico: si con el quedan nubes en el cielo, son vanilla y la
 * supresion no funciono. Es la unica forma de responder esa pregunta sin leer el log.
 */
public enum CloudMode {

    /** Nubes de Atmosia. Las vanilla quedan suprimidas mientras dure. */
    ATMOSIA("Atmosia", "Las nubes procedurales del mod. Las vanilla se apagan."),

    /** Las nubes originales del juego, como si el mod no estuviera. */
    VANILLA("Vanilla", "Las nubes originales de Minecraft. Atmosia no dibuja nada."),

    /** Ninguna. Ni las de Atmosia ni las del juego. */
    NONE("Ninguna", "Cielo despejado: ni las del mod ni las del juego.");

    private final String displayName;
    private final String description;

    CloudMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    /** Si Atmosia tiene que construir y dibujar geometria. */
    public boolean drawsAtmosia() {
        return this == ATMOSIA;
    }

    /** Si el ajuste de nubes del juego tiene que quedar apagado. */
    public boolean suppressesVanilla() {
        return this != VANILLA;
    }
}
