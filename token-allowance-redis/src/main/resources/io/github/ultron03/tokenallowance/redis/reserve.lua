-- Reserves an estimate on every budget, or on none if one of them has no room for it.
-- KEYS[1..n]       budget keys
-- KEYS[n+1..2n]    their sets of open reservations, in the same order
-- ARGV[1], ARGV[2] estimate, as tokens and micros
-- ARGV[3]          now, in epoch seconds; reservations that timed out by then are dropped first
-- ARGV[4]          when this reservation times out, in epoch seconds
-- ARGV[5]          reservation id
-- ARGV[3*i + 3..]  for KEYS[i]: max tokens, max micros (-1 for no cap) and expiry in epoch seconds
-- Returns 0 when reserved, otherwise i for the first KEYS[i] that has no room.
-- A set member is "id:tokens:micros", so a timed-out reservation can be taken off without other state.
-- Lua compares as doubles, which is exact up to 2^53 tokens or micros.
-- Field names match the constants in RedisKeys.

local n = #KEYS / 2
local tokens = tonumber(ARGV[1])
local micros = tonumber(ARGV[2])
local member = ARGV[5] .. ':' .. ARGV[1] .. ':' .. ARGV[2]

local function exceeds(total, cap)
	return cap >= 0 and total > cap
end

-- Redis rejects "-0", so a zero amount is skipped.
local function takeOff(key, field, amount)
	if amount ~= '0' then
		redis.call('HINCRBY', key, field, '-' .. amount)
	end
end

-- Kept even when this reservation is refused: timed-out reservations no longer count either way.
for i = 1, n do
	local timedOut = redis.call('ZRANGEBYSCORE', KEYS[n + i], '-inf', ARGV[3])
	for _, old in ipairs(timedOut) do
		local oldTokens, oldMicros = string.match(old, ':(%d+):(%d+)$')
		takeOff(KEYS[i], 'reserved_tokens', oldTokens)
		takeOff(KEYS[i], 'reserved_micros', oldMicros)
	end
	if #timedOut > 0 then
		redis.call('ZREMRANGEBYSCORE', KEYS[n + i], '-inf', ARGV[3])
	end
end

-- Check every budget before writing any, so a denial leaves all of them unchanged.
for i = 1, n do
	local spend = redis.call('HMGET', KEYS[i],
		'committed_tokens', 'reserved_tokens', 'committed_micros', 'reserved_micros')
	local tokensAfter = (tonumber(spend[1]) or 0) + (tonumber(spend[2]) or 0) + tokens
	local microsAfter = (tonumber(spend[3]) or 0) + (tonumber(spend[4]) or 0) + micros
	local arg = 3 * i + 3
	if exceeds(tokensAfter, tonumber(ARGV[arg])) or exceeds(microsAfter, tonumber(ARGV[arg + 1])) then
		return i
	end
end

for i = 1, n do
	local expiry = ARGV[3 * i + 5]
	redis.call('HINCRBY', KEYS[i], 'reserved_tokens', ARGV[1])
	redis.call('HINCRBY', KEYS[i], 'reserved_micros', ARGV[2])
	redis.call('EXPIREAT', KEYS[i], expiry)
	redis.call('ZADD', KEYS[n + i], ARGV[4], member)
	redis.call('EXPIREAT', KEYS[n + i], expiry)
end
return 0
