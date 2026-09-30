#version 150

in vec4 vertexColor;
in vec2 texCoord0;

uniform sampler2D Sampler0;

out vec4 fragColor;

// 1.21.1 legacy 高亮脉冲片元着色器：在 footprint.png 原色与纯白之间随时间往复闪烁。
// 脉冲强度由顶点色 alpha 承载（CPU 端按全局游戏时间算 sin 波写入），输出不透明度恒等于贴图 alpha
// —— 不再像旧的近似实现那样把整体 alpha 压到 0（那会造成“全透明↔原色”的闪烁而非“原色↔纯白”）。
// 不采样 lightmap → 不受世界光照（fullbright）；穿墙由 CPU 端即时绘制时 disableDepthTest 保证。
void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }

    float pulse = clamp(vertexColor.a, 0.0, 1.0);
    vec3 rgb = mix(tex.rgb, vec3(1.0), pulse);

    fragColor = vec4(rgb, tex.a);
}
