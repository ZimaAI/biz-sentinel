import type { Cell, ResultRow } from '~/types/commerce';

/** Round decimal strings without passing financial values through binary floating point. */
function scaled(value: string, digits: number, shift = 0): bigint | null {
	const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value);
	if (!match) return null;
	const negative = match[1] === '-',
		fraction = match[3] || '';
	let number = BigInt(match[2] + fraction),
		places = digits + shift - fraction.length;
	if (places >= 0) number *= 10n ** BigInt(places);
	else {
		const divisor = 10n ** BigInt(-places);
		number = (number + divisor / 2n) / divisor;
	}
	return negative ? -number : number;
}
export function formatDecimal(
	value: string | null | undefined,
	unit: string = 'COUNT',
	digits?: number,
): string {
	if (value == null) return '—';
	const decimals = digits ?? (unit === 'COUNT' ? 0 : 2),
		shift = unit === 'CNY_CENT' ? -2 : unit === 'RATIO' ? 2 : 0;
	const numeric = scaled(value, decimals, shift);
	if (numeric === null) return '—';
	const negative = numeric < 0n,
		absolute = (negative ? -numeric : numeric)
			.toString()
			.padStart(decimals + 1, '0');
	const integer = (decimals ? absolute.slice(0, -decimals) : absolute).replace(
		/\B(?=(\d{3})+(?!\d))/g,
		',',
	);
	return `${negative ? '−' : ''}${unit === 'CNY_CENT' ? '¥' : ''}${integer}${decimals ? '.' + absolute.slice(-decimals) : ''}${unit === 'RATIO' ? '%' : unit === 'PP' ? ' pp' : ''}`;
}
export function formatCell(cell?: Cell | null): string {
	return cell ? formatDecimal(cell.value, cell.unit) : '—';
}
export function getCell(
	row: ResultRow | null | undefined,
	field: string,
): Cell | undefined {
	return row?.cells?.find((cell) => cell.field === field);
}
export function previousDate(date: string, days = 1): string {
	return new Date(Date.parse(`${date}T00:00:00Z`) - days * 86400000)
		.toISOString()
		.slice(0, 10);
}
export function dateRangeLabel(
	range?: { start: string; endExclusive: string } | null,
): string {
	if (!range) return '—';
	const end = previousDate(range.endExclusive);
	return range.start === end ? range.start : `${range.start} — ${end}`;
}
export const runStatusLabels: Record<string, string> = {
	QUEUED: '排队中',
	PLANNING: '规划中',
	WAITING_APPROVAL: '待确认计划',
	RUNNING: '调查中',
	CANCEL_REQUESTED: '正在取消',
	SUCCEEDED: '已完成',
	PARTIAL: '部分完成',
	FAILED: '执行失败',
	CANCELLED: '已取消',
	EXPIRED: '已过期',
	ACCESS_REVOKED: '授权已变更',
};
export function chartValue(cell?: Cell): number | null {
	if (!cell?.value) return null;
	const match = /^-?(\d+)(?:\.(\d+))?$/.exec(cell.value);
	if (!match) return null;
	// Compare the original decimal before Number can round an out-of-range fraction
	// back onto MAX_SAFE_INTEGER; percentage conversion also changes the magnitude.
	const fraction = match[2] || '',
		magnitude = BigInt(match[1] + fraction);
	const maximum =
		BigInt(Number.MAX_SAFE_INTEGER) * 10n ** BigInt(fraction.length);
	if (
		magnitude > maximum ||
		(cell.unit === 'RATIO' && magnitude * 100n > maximum)
	)
		return null;
	const value = Number(cell.value);
	return cell.unit === 'CNY_CENT'
		? value / 100
		: cell.unit === 'RATIO'
			? value * 100
			: value;
}
