package dev.mopiux.atmosia.core;

/**
 * Como se dibuja el cielo. Es la decision estructural del mod, no un ajuste de calidad.
 *
 * Las tres tecnicas usan EXACTAMENTE el mismo campo de densidad, las mismas tres capas, el mismo
 * viento y la misma cobertura. Lo unico que cambia es que geometria se emite y como se mezcla. Por
 * eso se pueden comparar: el cielo es el mismo, cambia como se lo dibuja.
 */
public enum RenderTechnique {

    /**
     * Planos horizontales apilados. La tecnica original.
     *
     * El cielo se parte en regiones de 256 bloques y cada una se dibuja por separado. Eso produce
     * rectas visibles en los limites entre regiones: la mezcla con transparencia depende del orden,
     * y el orden cambia de golpe al cruzar un limite. Se corrigieron cinco causas distintas y
     * siempre aparecio una sexta, porque el problema es estructural y no de ajuste.
     */
    SLICES("Planos apilados", "La tecnica original. Es la que muestra lineas rectas en el cielo."),

    /**
     * Nubes por sprites: cuadrados con textura que siempre miran a la camara.
     *
     * No hay grilla de geometria, asi que no hay ninguna recta que pueda hacerse visible. El error
     * de orden sigue existiendo -es inherente a la transparencia- pero se reparte como ruido
     * desordenado en vez de concentrarse en una linea, y el ojo no detecta ruido de bajo contraste.
     */
    SPRITES("Sprites", "Nubes de bultos redondeados. Sin geometria en grilla, sin rectas."),

    /**
     * Ray marching: por cada pixel se avanza a pasos dentro de un volumen acumulando densidad.
     *
     * No hay geometria en absoluto: un solo dibujado para todo el cielo. Las costuras no se mitigan,
     * dejan de existir. Es la unica de las tres que puede representar volumen de verdad, con luz
     * atravesando la nube, y por eso es la unica con techo para nubes de tormenta.
     */
    RAYMARCH("Ray marching", "Volumen real por rayos. Sin geometria, sin costuras de ningun tipo.");

    private final String displayName;
    private final String description;

    RenderTechnique(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    /** Si esta tecnica construye mallas por region que haya que cachear. */
    public boolean usesRegionMeshes() {
        return this != RAYMARCH;
    }
}
