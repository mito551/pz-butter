-- PZButter rightDoubleTap: vanilla swallows the second press of a quick right-click pair. onObjectRightMouseButtonDown
-- marks the pair isDoubleClick (UIManager.isDoubleClick on lastRClickTime); the right-click-up path then runs the
-- no-op doRDoubleClick (an isSeen check, nothing else) and returns, so no menu opens. Wrap doRDoubleClick: run the
-- stock body, then call doRClick only while the pair sits inside vanilla's own 500 ms hold-to-aim gate, so a held
-- press (aim) never becomes a menu.
-- Reload safety: the wrapper is a fresh closure keeping the original as its upvalue; the live table is stamped with
-- the installed wrapper. A stamp present while the live function is not ours means another mod sat on top: leave
-- that chain alone. The file (media/lua/server/ISObjectClickHandler.lua) re-runs on each world load, which restores
-- the vanilla function and drops the stamp, so each load installs fresh.
if not PZButter then return end

local stock

local function install()
    local t = ISObjectClickHandler
    if t == nil or t.doRDoubleClick == nil or t.doRClick == nil then return end
    local prev = rawget(t, "_pzbutterRDT")
    if prev ~= nil then
        if t.doRDoubleClick ~= prev then return end -- another mod sits on top of our stamp: leave the chain
        return -- ours already: fresh reinstall only happens through a world reload (fresh vanilla + no stamp)
    end
    stock = t.doRDoubleClick
    t.doRDoubleClick = function(object, x, y)
        stock(object, x, y)
        if PZButter.setting("rightDoubleTap") then
            local last = t.lastRClickTime
            if last ~= nil and getTimestampMs() - last < 500 then
                t.doRClick(object, x, y)
            end
        end
    end
    rawset(t, "_pzbutterRDT", t.doRDoubleClick)
end

install()
