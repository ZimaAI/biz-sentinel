/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, expect, it } from 'vitest';
import {
	chartValue,
	dateRangeLabel,
	formatCell,
	formatDecimal,
	getCell,
	previousDate,
} from './commerceFormat';
import type { Cell, ResultRow } from '../types/commerce';

const cell = (value: string | null, unit: Cell['unit']): Cell => ({
	field: 'metric',
	value,
	unit,
});

describe('Commerce decimal formatting', () => {
	it('converts backend cents to yuan exactly and groups the integer part', () => {
		expect(formatDecimal('48620000', 'CNY_CENT')).toBe('¥486,200.00');
		expect(formatDecimal('1', 'CNY_CENT')).toBe('¥0.01');
		expect(formatDecimal('-11380000', 'CNY_CENT')).toBe('−¥113,800.00');
	});

	it('rounds fractional cents HALF_UP, including negative ties', () => {
		expect(formatDecimal('100.4999', 'CNY_CENT')).toBe('¥1.00');
		expect(formatDecimal('100.5', 'CNY_CENT')).toBe('¥1.01');
		expect(formatDecimal('-100.5', 'CNY_CENT')).toBe('−¥1.01');
		expect(formatDecimal('999.5', 'CNY_CENT')).toBe('¥10.00');
	});

	it('preserves integer and cent precision beyond Number.MAX_SAFE_INTEGER', () => {
		expect(formatDecimal('9007199254740993', 'COUNT')).toBe(
			'9,007,199,254,740,993',
		);
		expect(formatDecimal('9007199254740993', 'CNY_CENT')).toBe(
			'¥90,071,992,547,409.93',
		);
		expect(formatDecimal('-9007199254740993.5', 'CNY_CENT')).toBe(
			'−¥90,071,992,547,409.94',
		);
	});

	it('converts ratios to percentages while leaving percentage-point values unscaled', () => {
		expect(formatDecimal('0.04', 'RATIO')).toBe('4.00%');
		expect(formatDecimal('0.0448', 'RATIO')).toBe('4.48%');
		expect(formatDecimal('-0.0048', 'RATIO')).toBe('−0.48%');
		expect(formatDecimal('-0.48', 'PP')).toBe('−0.48 pp');
		expect(formatDecimal('1.234567', 'RATIO')).toBe('123.46%');
	});

	it('avoids binary floating-point rounding errors for ratios', () => {
		expect(formatDecimal('0.01005', 'RATIO')).toBe('1.01%');
		expect(formatDecimal('-0.01005', 'RATIO')).toBe('−1.01%');
		expect(formatDecimal('0.99995', 'RATIO')).toBe('100.00%');
	});

	it('respects explicit precision, including zero decimal places', () => {
		expect(formatDecimal('155', 'CNY_CENT', 0)).toBe('¥2');
		expect(formatDecimal('0.03456', 'RATIO', 3)).toBe('3.456%');
		expect(formatDecimal('2.505', 'COUNT', 2)).toBe('2.51');
		expect(formatDecimal('-2.5', 'COUNT')).toBe('−3');
	});

	it('distinguishes missing values from real zeros and suppresses rounded negative zero', () => {
		expect(formatDecimal(null, 'CNY_CENT')).toBe('—');
		expect(formatDecimal(undefined, 'RATIO')).toBe('—');
		expect(formatDecimal('0', 'CNY_CENT')).toBe('¥0.00');
		expect(formatDecimal('0', 'COUNT')).toBe('0');
		expect(formatDecimal('-0.00004', 'RATIO')).toBe('0.00%');
	});

	it.each(['', 'NaN', 'Infinity', '-Infinity', '1e3', ' 12 ', '12,000', '--1'])(
		'does not render malformed decimal %j as a business value',
		(value) => expect(formatDecimal(value, 'CNY_CENT')).toBe('—'),
	);

	it('looks up comparison cells by their full field name without replacing missing cells with zero', () => {
		const row: ResultRow = {
			rowKey: 's1',
			dimensions: { store: 's1' },
			cells: [
				{ field: 'paid_gmv', value: '25500000', unit: 'CNY_CENT' },
				{ field: 'paid_gmv_delta', value: '-10500000', unit: 'CNY_CENT' },
			],
		};
		expect(formatCell(getCell(row, 'paid_gmv_delta'))).toBe('−¥105,000.00');
		expect(formatCell(getCell(row, 'refund_amount'))).toBe('—');
		expect(getCell(null, 'paid_gmv')).toBeUndefined();
		expect(formatCell(cell(null, 'RATIO'))).toBe('—');
	});
});

describe('Commerce chart values', () => {
	it('uses chart units consistent with formatted money, ratios and percentage points', () => {
		expect(chartValue(cell('48620000', 'CNY_CENT'))).toBe(486200);
		expect(chartValue(cell('0.04', 'RATIO'))).toBe(4);
		expect(chartValue(cell('-0.48', 'PP'))).toBe(-0.48);
		expect(chartValue(cell('1842', 'COUNT'))).toBe(1842);
	});

	it('keeps real zero while treating missing and non-finite values as gaps', () => {
		expect(chartValue(cell('0', 'COUNT'))).toBe(0);
		expect(chartValue(cell(null, 'RATIO'))).toBeNull();
		expect(chartValue()).toBeNull();
		expect(chartValue(cell('Infinity', 'COUNT'))).toBeNull();
	});

	it('omits values outside safe chart precision instead of silently rounding them', () => {
		expect(chartValue(cell('9007199254740991', 'COUNT'))).toBe(
			Number.MAX_SAFE_INTEGER,
		);
		expect(chartValue(cell('9007199254740993', 'COUNT'))).toBeNull();
		expect(chartValue(cell('-9007199254740993', 'CNY_CENT'))).toBeNull();
		expect(chartValue(cell('9007199254740991.1', 'COUNT'))).toBeNull();
	});

	it('also enforces safe magnitude after a ratio is converted to percent', () => {
		expect(chartValue(cell('90071992547410', 'RATIO'))).toBeNull();
		expect(chartValue(cell('-90071992547410', 'RATIO'))).toBeNull();
	});
});

describe('Commerce half-open business-date labels', () => {
	it('shows a single complete business day without repeating its end date', () => {
		expect(
			dateRangeLabel({ start: '2026-09-20', endExclusive: '2026-09-21' }),
		).toBe('2026-09-20');
		expect(
			dateRangeLabel({ start: '2026-09-14', endExclusive: '2026-09-21' }),
		).toBe('2026-09-14 — 2026-09-20');
	});

	it.each([
		['2026-01-01', '2025-12-31'],
		['2026-03-01', '2026-02-28'],
		['2024-03-01', '2024-02-29'],
		['2000-03-01', '2000-02-29'],
		['2100-03-01', '2100-02-28'],
		['2026-11-02', '2026-11-01'],
	])(
		'subtracts days across calendar boundaries using UTC: %s',
		(end, expected) => {
			expect(previousDate(end)).toBe(expected);
		},
	);

	it('supports an explicit whole-day offset and keeps missing ranges visibly absent', () => {
		expect(previousDate('2026-09-21', 7)).toBe('2026-09-14');
		expect(previousDate('2026-09-21', 0)).toBe('2026-09-21');
		expect(dateRangeLabel(null)).toBe('—');
		expect(dateRangeLabel()).toBe('—');
	});
});
