#version 330
#extension GL_ARB_separate_shader_objects : require

// Marks guessed terrain: every pixel whose world position lies in a guessed chunk is tinted blue with
// diagonal stripes. The position comes from the depth buffer; the chunk mask is a size x size texture
// of chunks starting at the mask origin.

uniform sampler2D InSampler;
uniform sampler2D MaskSampler;

layout(std140) uniform GuessInfo {
    mat4 InvViewProj;
    vec4 CameraFromOrigin; // camera position relative to the mask origin (blocks), w unused
    vec4 Params;           // x: mask size (chunks), y: opacity, z: 1 if clip depth is 0..1 (else -1..1), w: time
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(InSampler, texCoord).r;
    if (depth <= 0.0 || depth >= 1.0) discard; // sky (reverse-Z clears to 0)
    float ndcZ = Params.z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, ndcZ, 1.0);
    vec3 world = p.xyz / p.w + CameraFromOrigin.xyz;
    ivec2 cell = ivec2(floor(world.xz / 16.0));
    int size = int(Params.x);
    if (cell.x < 0 || cell.y < 0 || cell.x >= size || cell.y >= size) discard;
    if (texelFetch(MaskSampler, cell, 0).r < 0.5) discard;
    float stripe = step(0.78, fract((world.x + world.z + world.y * 0.5) / 5.0));
    vec2 inChunk = fract(world.xz / 16.0);
    float border = step(min(min(inChunk.x, 1.0 - inChunk.x), min(inChunk.y, 1.0 - inChunk.y)), 0.02);
    fragColor = vec4(0.30, 0.55, 1.0, Params.y * (0.5 + 0.5 * max(stripe, border)));
}
