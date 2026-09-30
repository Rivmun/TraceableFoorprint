#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord0;

// 1.21.1 legacy 专用脉冲顶点着色器（不含雾 / ChunkOffset / ColorModulator，保持自包含）。
// 与新版共用 footprint_pulse 的语义无关：此处只需把 footprint 局部四边形经 ModelViewMat*ProjMat 投到裁剪空间。
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
}
