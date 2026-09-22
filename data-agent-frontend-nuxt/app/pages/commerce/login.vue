<script setup lang="ts">
import { commerceApi } from '~/services/commerceApi';
definePageMeta({ layout: 'commerce' });
const username = ref(''),
	password = ref(''),
	error = ref(''),
	busy = ref(false),
	showCredentials = ref(false),
	context = useCommerceContext();
async function guestSubmit() {
	if (busy.value) return;
	busy.value = true;
	error.value = '';
	try {
		await commerceApi.guestLogin();
		await context.refresh();
		await navigateTo('/commerce/overview');
	} catch (e) {
		error.value = e instanceof Error ? e.message : '游客入口暂不可用';
	} finally {
		busy.value = false;
	}
}
async function submit() {
	if (busy.value) return;
	busy.value = true;
	error.value = '';
	try {
		await commerceApi.login(username.value, password.value);
		await context.refresh();
		await navigateTo('/commerce/overview');
	} catch (e) {
		error.value = e instanceof Error ? e.message : '登录失败';
	} finally {
		busy.value = false;
	}
}
</script>
<template>
	<main class="login-page">
		<div class="login-story">
			<div class="cl-eyebrow">COMMERCE LENS / 商脉</div>
			<h1>每一次经营变化，<br />都有迹可循。</h1>
			<p>
				从指标到证据，从问题到行动。<br />让多店铺经营的每一个判断，都有可信的依据。
			</p>
			<div class="story-lines">
				<div><CommerceIcon name="grid" />统一经营视角</div>
				<div><CommerceIcon name="sparkles" />可审查的诊断过程</div>
				<div><CommerceIcon name="shield" />可追溯的数字证据</div>
			</div>
			<span class="login-footer">基于 Spring AI Alibaba DataAgent</span>
		</div>
		<section class="login-form">
			<img
				class="cl-logo"
				src="/brand/commerce-lens.svg"
				width="38"
				height="38"
				alt="商脉 CommerceLens"
			/>
			<h2>登录商脉工作台</h2>
			<p>先浏览一份真实经营分析演示，数据按授权范围展示。</p>
			<button
				class="cl-button primary guest-button"
				type="button"
				:disabled="busy"
				@click="guestSubmit"
			>
				{{ busy && !showCredentials ? '正在打开演示…' : '游客浏览演示' }}
				<CommerceIcon name="arrow-right" :size="16" />
			</button>
			<div v-if="error && !showCredentials" class="cl-banner error" role="alert">
				{{ error }}
			</div>
			<button
				class="credential-toggle"
				type="button"
				@click="showCredentials = !showCredentials; error = ''"
			>
				{{ showCredentials ? '收起账号入口' : '账号密码登录' }}
			</button>
			<form v-if="showCredentials" @submit.prevent="submit">
				<label class="cl-field"
					><span>账号</span
					><input
						v-model="username"
						autocomplete="username"
						required
						placeholder="请输入账号" /></label
				><label class="cl-field"
					><span>密码</span
					><input
						v-model="password"
						type="password"
						autocomplete="current-password"
						required
						placeholder="请输入密码"
				/></label>
				<div v-if="error" class="cl-banner error" role="alert">{{ error }}</div>
				<button
					class="cl-button primary"
					type="submit"
					:disabled="busy"
					style="width: 100%; margin-top: 8px"
				>
					{{ busy ? '正在进入工作空间…' : '进入工作空间'
					}}<CommerceIcon name="arrow-right" :size="16" />
				</button>
			</form>
			<small>游客仅可浏览演示数据；管理员账号可管理成员与授权范围。</small>
			<footer><IcpFiling /></footer>
		</section>
	</main>
</template>
<style scoped>
.login-page {
	min-height: 100vh;
	display: grid;
	grid-template-columns: 1.1fr 1fr;
	background: white;
}
.login-story {
	background: #f0f3ff;
	padding: 12vh 12%;
	position: relative;
	display: flex;
	flex-direction: column;
	justify-content: center;
}
.login-story h1 {
	font-size: 42px;
	line-height: 1.5;
	letter-spacing: -1px;
	margin: 30px 0 20px;
}
.login-story p {
	font-size: 15px;
	line-height: 2;
	color: var(--cl-secondary);
}
.story-lines {
	display: flex;
	flex-direction: column;
	gap: 18px;
	margin-top: 45px;
	color: #5768a6;
	font-size: 13px;
}
.story-lines div {
	display: flex;
	align-items: center;
	gap: 12px;
}
.login-footer {
	font-size: 11px;
	color: var(--cl-muted);
	margin-top: 65px;
}
.login-form {
	width: 370px;
	max-width: calc(100vw - 48px);
	align-self: center;
	justify-self: center;
}
.login-form h2 {
	font-size: 24px;
	margin-top: 24px;
}
.login-form > p {
	color: var(--cl-secondary);
	font-size: 13px;
	margin: 8px 0 32px;
}
.guest-button {
	width: 100%;
	margin-top: 6px;
}
.credential-toggle {
	display: block;
	margin: 18px auto 2px;
	border: 0;
	background: transparent;
	color: #9aa3ba;
	font-size: 10px;
	cursor: pointer;
	text-decoration: underline;
	text-underline-offset: 3px;
}
.credential-toggle:hover {
	color: var(--cl-secondary);
}
.login-form > small {
	display: block;
	text-align: center;
	margin-top: 25px;
	font-size: 11px;
}
.login-form .cl-field {
	margin-bottom: 20px;
}
@media (max-width: 959px) {
	.login-page {
		grid-template-columns: 1fr;
	}
	.login-story {
		display: none;
	}
	.login-form {
		margin: 40px 0;
	}
}
</style>
