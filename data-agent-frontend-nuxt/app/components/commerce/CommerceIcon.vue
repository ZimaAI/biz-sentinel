<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 });
const paths: Record<string, string> = {
	grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
	sparkles: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z',
	alert: 'M12 3 2 21h20L12 3Z M12 9v5 M12 17v.5',
	book: 'M12 5c-3-2-7-2-10-1v16c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1Zm0 0v16',
	database:
		'M21 5c0 2-4 3-9 3S3 7 3 5s4-3 9-3 9 1 9 3ZM3 5v14c0 2 4 3 9 3s9-1 9-3V5M3 12c0 2 4 3 9 3s9-1 9-3',
	file: 'M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 12h8M8 16h8',
	activity: 'M2 12h5l3-9 4 18 3-9h5',
	calendar: 'M4 5h16v16H4zM4 10h16M8 2v6M16 2v6',
	store: 'M4 11v10h16V11M2 7l3-4h14l3 4v4H2V7ZM9 21v-7h6v7',
	refresh: 'M20 8a8 8 0 0 0-14-3L3 8m0-6v6h6M4 16a8 8 0 0 0 14 3l3-3m0 6v-6h-6',
	'chevron-down': 'm6 9 6 6 6-6',
	'arrow-right': 'M4 12h16m-6-6 6 6-6 6',
	check: 'm4 12 5 5L20 6',
	close: 'm6 6 12 12M6 18 18 6',
	plus: 'M12 4v16M4 12h16',
	search: 'M21 21l-6-6M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
	upload: 'M12 16V3m-5 5 5-5 5 5M3 15v6h18v-6',
	download: 'M12 3v13m-5-5 5 5 5-5M3 16v5h18v-5',
	copy: 'M8 8h13v13H8zM4 16H2V2h14v2',
	settings: 'M4 5h16M4 12h16M4 19h16M8 2v6M16 9v6M10 16v6',
	logout: 'M9 4H3v16h6M9 12h12m-5-5 5 5-5 5',
	menu: 'M3 5h18M3 12h18M3 19h18',
	bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',
	shield: 'm12 2 9 4v6c0 6-9 10-9 10S3 18 3 12V6l9-4Zm-5 9 3 3 6-6',
	info: 'M12 10v7m0-11v.5M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
};
</script>
<template>
	<svg
		:width="size"
		:height="size"
		viewBox="0 0 24 24"
		fill="none"
		stroke="currentColor"
		stroke-width="1.5"
		stroke-linecap="round"
		stroke-linejoin="round"
		aria-hidden="true"
	>
		<path :d="paths[name] || paths.sparkles" />
	</svg>
</template>
