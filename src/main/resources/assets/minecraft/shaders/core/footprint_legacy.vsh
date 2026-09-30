#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord0;

// legacy（<=1.21.1）高亮脉冲顶点着色器（不含雾 / ChunkOffset / ColorModulator，保持自包含）。
// 与新版 footprint_pulse 的语义无关：此处只需把 footprint 局部四边形经 ModelViewMat*ProjMat 投到裁剪空间。
// in 声明顺序刻意与 DefaultVertexFormat.POSITION_TEX_COLOR 的元素顺序（Position, UV0, Color）一致：
// 1.20.1 的 ShaderInstance 只有 json 写了 "attributes" 才会 glBindAttribLocation，否则退化为按声明顺序的
// 隐式 location，与 VBO 按格式元素顺序设置的 attrib 指针错位（颜色与 UV 互换 → 采样到贴图空处 → 全透明）。
// json 已补 attributes 做显式绑定，此处对齐声明顺序作为第二道保险（1.21.1 无条件显式绑定，顺序无关）。
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
}
