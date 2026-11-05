--[[
    IS_Perf: load-after performance patch for ImmersiveSnow.

    Uses ImmersiveSnow's central ISnow.listen registry to merge every registered
    per-tick/per-frame handler into a single listener per event type. ISnow records
    each registration as {event, module, fn}, so the identities needed to remove and
    re-add them are still available at game start.

    ISnow.handlers is left intact: the Viewpoint decal handover and ISnow.bench call
    the fns from it directly. Handlers registered after install stay separate.

    Event.Add/Remove take the closure as argument 0, so they must be called with a
    dot (Events.X.Add(fn)); the colon form passes the event table and does nothing.
]]

local MERGE = {
    OnTick = true,
    OnTickEvenPaused = true,
    OnPlayerUpdate = true,
    OnPostRender = true,
    RenderOpaqueObjectsInWorld = true,
}

local installed = false

local function install()
    if installed then return true end
    if not ISnow or type(ISnow.handlers) ~= "table" then return false end
    installed = true

    local buckets, order = {}, {}
    for _, h in ipairs(ISnow.handlers) do
        local event = MERGE[h.event] and Events[h.event]
        if event then
            if not buckets[h.event] then
                buckets[h.event] = { event = event, handlers = {} }
                order[#order + 1] = h.event
            end
            table.insert(buckets[h.event].handlers, h)
        end
    end

    for _, eventName in ipairs(order) do
        local bucket = buckets[eventName]
        local hs = bucket.handlers
        for i = 1, #hs do
            bucket.event.Remove(hs[i].fn)
        end
        bucket.event.Add(function(a, b, c, d, e, f)
            for i = 1, #hs do
                local h = hs[i]
                local ok, err = pcall(h.fn, a, b, c, d, e, f)
                if not ok then
                    print("[IS_Perf] " .. tostring(h.module or "?") .. " [" .. eventName .. "] raised: " .. tostring(err))
                end
            end
        end)
    end

    print("[IS_Perf] merged handlers for: " .. table.concat(order, ", "))
    return true
end

Events.OnGameStart.Add(function()
    local ok, err = pcall(install)
    if not ok then print("[IS_Perf] install failed: " .. tostring(err)) end
end)
