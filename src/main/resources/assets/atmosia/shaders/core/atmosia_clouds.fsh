#version 150

// Ray marching de nubes.
//
// Por cada pixel se avanza a pasos dentro del volumen de nube acumulando densidad. No hay
// geometria, no hay regiones y no hay draw calls que ordenar: por eso no puede haber costuras.
//
// Las tres capas y sus parametros son los mismos que usan las otras dos tecnicas.

uniform sampler2D Sampler0;          // ruido, 256x256, repetido

uniform mat4 AtmInverseViewProj;     // de clip a espacio relativo a la camara
uniform vec3 AtmCameraPos;           // posicion absoluta de la camara
uniform vec3 AtmSkyColor;
uniform vec3 AtmCloudTint;
uniform vec3 AtmSunDir;
uniform vec4 AtmParams;              // x=cobertura  y=alcance  z=tiempo  w=pasos
uniform vec4 AtmWind0;               // capa0.xz, capa1.xz
uniform vec4 AtmWind1;               // capa2.xz

in vec2 pantalla;
out vec4 fragColor;

const float PI = 3.14159265;

// Las tres capas: base, espesor, escala de ruido, cobertura propia
const vec4 CAPA0 = vec4(172.0, 16.0, 300.0, 0.42);
const vec4 CAPA1 = vec4(192.0, 20.0, 420.0, 0.36);
const vec4 CAPA2 = vec4(216.0, 14.0, 620.0, 0.26);

const vec3 COLOR0 = vec3(0.88, 0.89, 0.92);
const vec3 COLOR1 = vec3(0.96, 0.96, 0.97);
const vec3 COLOR2 = vec3(0.92, 0.94, 1.00);

float ruido(vec2 p) {
    return texture(Sampler0, p * (1.0 / 256.0)).r;
}

float fbm(vec2 p) {
    float suma = 0.0;
    float amp = 1.0;
    float norm = 0.0;
    for (int i = 0; i < 4; i++) {
        suma += ruido(p) * amp;
        norm += amp;
        amp *= 0.5;
        p *= 2.0;
    }
    return suma / norm;
}

// Densidad de una capa en un punto del mundo
float densidadCapa(vec3 pos, vec4 capa, vec2 viento, float escalaCobertura) {
    float t = (pos.y - capa.x) / capa.y;
    if (t < 0.0 || t > 1.0) {
        return 0.0;
    }
    // perfil vertical suave: la capa se afina hacia arriba y hacia abajo
    float perfil = sin(PI * t);
    perfil *= perfil;

    vec2 cloudPos = (pos.xz - viento) / capa.z;
    float crudo = fbm(cloudPos);

    float cobertura = clamp(capa.w * escalaCobertura, 0.0, 0.95);
    float piso = 1.0 - cobertura;
    if (crudo <= piso) {
        return 0.0;
    }
    float d = (crudo - piso) / max(1e-6, 1.0 - piso);
    return clamp(d, 0.0, 1.0) * perfil;
}

void main() {
    // Direccion del rayo de este pixel, reconstruida desde la matriz inversa
    vec4 clip = vec4(pantalla * 2.0 - 1.0, 1.0, 1.0);
    vec4 mundo = AtmInverseViewProj * clip;
    vec3 dir = normalize(mundo.xyz / mundo.w);

    float alcance = AtmParams.y;
    int pasos = int(AtmParams.w);
    float escalaCobertura = AtmParams.x;

    float yBase = CAPA0.x;
    float yTecho = CAPA2.x + CAPA2.y;

    // Entrada y salida de la banda de nubes
    float t0, t1;
    if (abs(dir.y) < 1e-4) {
        discard;
    }
    float ta = (yBase - AtmCameraPos.y) / dir.y;
    float tb = (yTecho - AtmCameraPos.y) / dir.y;
    t0 = min(ta, tb);
    t1 = max(ta, tb);
    t0 = max(t0, 0.0);
    t1 = min(t1, alcance * 1.6);
    if (t1 <= t0) {
        discard;
    }

    float ds = (t1 - t0) / float(pasos);

    // Desorden por pixel: rompe el bandeo de los pasos discretos sin costar nada
    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);

    vec3 acum = vec3(0.0);
    float T = 1.0;
    const float SIGMA = 6.0;

    for (int i = 0; i < 256; i++) {
        if (i >= pasos || T < 0.012) {
            break;
        }
        float t = t0 + (float(i) + jitter) * ds;
        vec3 pos = AtmCameraPos + dir * t;

        float d0 = densidadCapa(pos, CAPA0, AtmWind0.xy, escalaCobertura);
        float d1 = densidadCapa(pos, CAPA1, AtmWind0.zw, escalaCobertura);
        float d2 = densidadCapa(pos, CAPA2, AtmWind1.xy, escalaCobertura);

        float d = d0 + d1 + d2;
        if (d <= 0.0005) {
            continue;
        }

        vec3 color = (COLOR0 * d0 + COLOR1 * d1 + COLOR2 * d2) / d;

        // Altura dentro de la capa que domina, para el gradiente vertical
        vec4 capa = d0 >= d1 && d0 >= d2 ? CAPA0 : (d1 >= d2 ? CAPA1 : CAPA2);
        float ht = clamp((pos.y - capa.x) / capa.y, 0.0, 1.0);
        float sombra = 0.78 + 0.22 * ht;

        // Dispersion hacia adelante: borde luminoso mirando hacia el sol
        float haciaSol = max(0.0, dot(dir, AtmSunDir));
        float brillo = 1.0 + 0.35 * pow(haciaSol, 6.0);

        // Niebla de distancia, por muestra: el borde del domo se disuelve sin ningun escalon
        float fade = 1.0 - smoothstep(alcance * 0.25, alcance, t);

        vec3 muestra = mix(AtmSkyColor, color * sombra * brillo * AtmCloudTint, fade);
        float alfa = 1.0 - exp(-SIGMA * d * ds * 0.01);

        acum += T * muestra * alfa;
        T *= (1.0 - alfa);
    }

    float cubierto = 1.0 - T;
    if (cubierto < 0.004) {
        discard;
    }
    fragColor = vec4(acum / max(cubierto, 1e-4), cubierto);
}
