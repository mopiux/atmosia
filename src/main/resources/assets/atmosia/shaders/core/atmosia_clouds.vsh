#version 150

// Cuadrilatero que cubre la pantalla. No hay geometria de nubes: la nube se calcula por pixel.

in vec3 Position;
in vec2 UV0;

out vec2 pantalla;

void main() {
    gl_Position = vec4(Position.xy, 1.0, 1.0);   // z=1: al fondo, detras del terreno
    pantalla = UV0;
}
