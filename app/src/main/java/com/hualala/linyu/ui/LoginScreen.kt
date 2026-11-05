package com.hualala.linyu.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.Autofill
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hualala.linyu.R
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 把一个输入框接入系统自动填充。两件事缺一不可：
 * 1. 把控件在屏幕上的位置告诉 AutofillNode（系统要靠它把高亮框画对位置）
 * 2. 控件获得焦点时主动向系统发一次请求，密码管理器才会弹出「填充/保存」条
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.loginAutofill(node: AutofillNode, autofill: Autofill?): Modifier =
    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { if (it.isFocused) autofill?.requestAutofillForNode(node) }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(),
    onLoginSuccess: (String) -> Unit
) {
    var animated by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (animated) 1f else 0.8f, tween(400))

    // ── 系统自动填充（保存/回填账号密码）──
    // 本项目用的 Compose 1.6 还没有官方那套 `Modifier.semantics { contentType = ... }`
    // （那个 API 到 Compose 1.8 才有），所以这里用官方暴露的底层三件套自己接：
    //   AutofillNode（描述这个输入框是什么）+ AutofillTree（注册给系统）+ LocalAutofill（触发请求）
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current

    val phoneAutofill = remember {
        AutofillNode(listOf(AutofillType.Username)) { viewModel.phone = it }
    }
    val passwordAutofill = remember {
        AutofillNode(listOf(AutofillType.Password)) { viewModel.password = it }
    }
    val smsAutofill = remember {
        AutofillNode(listOf(AutofillType.SmsOtpCode)) { viewModel.smsCode = it }
    }

    // 注册到系统 Autofill 树；离开页面时摘掉，避免切来切去堆积无用节点
    DisposableEffect(Unit) {
        val nodes = listOf(phoneAutofill, passwordAutofill, smsAutofill)
        nodes.forEach { autofillTree += it }
        onDispose { nodes.forEach { autofillTree.children.remove(it.id) } }
    }

    LaunchedEffect(viewModel.loginResult) {
        val result = viewModel.loginResult
        if (result != null && result.isSuccess) {
            onLoginSuccess(viewModel.phone)
            viewModel.resetResult()
        }
    }

    LaunchedEffect(Unit) { animated = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A73E8),
                        Color(0xFF0D47A1),
                        Color(0xFF051D40)
                    )
                )
            )
    ) {
        // 装饰圆
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = (-120).dp, y = (-80).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.04f))
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = 60.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .offset(y = (-50).dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 90.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Logo
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "logo",
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(18.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text("淋 浴", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Hualala · 校园热水", fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(20.dp))

            // 登录卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("欢迎回来", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                    Text("请使用校园账号登录", fontSize = 13.sp, color = Color.Gray,
                        modifier = Modifier.padding(bottom = 20.dp, top = 4.dp))

                    // 密码/验证码切换
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { viewModel.switchMode() }) {
                            Text(if (viewModel.isSmsMode) "密码登录" else "验证码登录",
                                color = Color(0xFF1A73E8), fontSize = 13.sp)
                        }
                    }
                    // 当前模式的一句说明。放这儿是因为用户最容易卡住的两个点都在这：
                    // 没注册的人不知道能不能直接用（能，会自动注册），
                    // 没设过密码的人不知道密码该填什么（填不了，用验证码）
                    Text(
                        if (viewModel.isSmsMode) "未注册的手机号将自动创建账号"
                        else "未设置密码？请使用手机号验证码登录",
                        fontSize = 12.sp, color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = viewModel.phone,
                        onValueChange = { viewModel.phone = it },
                        label = { Text("手机号") },
                        leadingIcon = { Icon(Icons.Default.Phone, null, tint = Color(0xFF1A73E8)) },
                        trailingIcon = { if (viewModel.phone.isNotEmpty()) TextButton(onClick = { viewModel.phone = "" }) { Text("✕", color = Color.Gray) } },
                        modifier = Modifier.fillMaxWidth().loginAutofill(phoneAutofill, autofill),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp),
                        colors = loginFieldColors()
                    )
                    Spacer(Modifier.height(14.dp))

                    if (viewModel.isSmsMode) {
                        // 验证码输入
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = viewModel.smsCode,
                                onValueChange = { viewModel.smsCode = it },
                                label = { Text("验证码") },
                                modifier = Modifier.weight(1f).loginAutofill(smsAutofill, autofill),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                colors = loginFieldColors()
                            )
                            Spacer(Modifier.width(12.dp))
                            // 固定宽度：文案在「发送」↔「60s」之间变化时按钮宽度不变，
                            // 否则会把左边 weight(1f) 的输入框挤窄/挤宽，看着像抖了一下
                            Button(
                                onClick = { viewModel.sendSmsCode() },
                                enabled = !viewModel.isSendingCode && viewModel.countdown == 0,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.width(84.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1A73E8),
                                    disabledContainerColor = Color(0xFF90CAF9)
                                )
                            ) {
                                Text(if (viewModel.countdown > 0) "${viewModel.countdown}s" else "发送",
                                    fontSize = 13.sp, color = Color.White, maxLines = 1)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = viewModel.password,
                            onValueChange = { viewModel.password = it },
                            label = { Text("密码") },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Color(0xFF1A73E8)) },
                            trailingIcon = { if (viewModel.password.isNotEmpty()) TextButton(onClick = { viewModel.password = "" }) { Text("✕", color = Color.Gray) } },
                            modifier = Modifier.fillMaxWidth().loginAutofill(passwordAutofill, autofill),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(14.dp),
                            colors = loginFieldColors()
                        )
                    }
                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.login() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !viewModel.isLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A73E8),
                            disabledContainerColor = Color(0xFF90CAF9)
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        if (viewModel.isLoading) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("登 录", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    viewModel.errorMessage?.let { msg ->
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFEBEE)
                        ) {
                            Text(com.hualala.linyu.utils.UserFeedback.message(msg, "登录失败，请重试"), modifier = Modifier.padding(12.dp),
                                color = Color(0xFFC62828), fontSize = 13.sp,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 登录页输入框配色。
 *
 * ⚠️ 必须写死，**不能用主题色**：登录页那张卡片是固定白底
 * （`Color.White`，不跟深浅模式走），而 `OutlinedTextField` 默认取
 * `MaterialTheme.colorScheme.onSurface`——深色模式下它是浅色的，
 * 浅色字压在白卡片上，输进去的内容看着就是灰蒙蒙一片。
 */
@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF1A73E8),
    unfocusedBorderColor = Color(0xFFBFC7D5),
    focusedLabelColor = Color(0xFF1A73E8),
    unfocusedLabelColor = Color(0xFF6B7280),
    focusedTextColor = Color(0xFF1A1A1A),
    unfocusedTextColor = Color(0xFF1A1A1A),
    cursorColor = Color(0xFF1A73E8)
)
