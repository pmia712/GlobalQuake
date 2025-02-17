package globalquake.intensity;

import globalquake.core.GlobalQuake;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.earthquake.data.Hypocenter;
import globalquake.core.events.GlobalQuakeEventAdapter;
import globalquake.core.events.specific.QuakeArchiveEvent;
import globalquake.core.events.specific.QuakeCreateEvent;
import globalquake.core.events.specific.QuakeRemoveEvent;
import globalquake.core.events.specific.QuakeUpdateEvent;
import globalquake.events.specific.ShakeMapsUpdatedEvent;
import globalquake.client.GlobalQuakeLocal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ShakemapService {

    private final Map<UUID, ShakeMap> shakeMaps = new HashMap<>();

    private final ExecutorService shakemapService = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService checkService = Executors.newSingleThreadScheduledExecutor();

    public ShakemapService(){
        GlobalQuake.instance.getEventHandler().registerEventListener(new GlobalQuakeEventAdapter(){
            @Override
            public void onQuakeCreate(QuakeCreateEvent event) {
                updateShakemap(event.earthquake());
            }

            @Override
            public void onQuakeArchive(QuakeArchiveEvent event) {
                removeShakemap(event.archivedQuake().getUuid());
            }

            @Override
            public void onQuakeUpdate(QuakeUpdateEvent event) {
                updateShakemap(event.earthquake());
            }

            @Override
            public void onQuakeRemove(QuakeRemoveEvent event) {
                removeShakemap(event.earthquake().getUuid());
            }
        });

        checkService.scheduleAtFixedRate(this::checkShakemaps, 0, 1, TimeUnit.MINUTES);
    }

    private void checkShakemaps() {
        for (Iterator<Map.Entry<UUID, ShakeMap>> iterator = shakeMaps.entrySet().iterator(); iterator.hasNext(); ) {
            var kv = iterator.next();
            UUID uuid = kv.getKey();
            if(GlobalQuake.instance.getEarthquakeAnalysis().getEarthquake(uuid) == null){
                iterator.remove();
            }
        }
    }

    private void removeShakemap(UUID uuid) {
        shakemapService.submit(() -> {
            shakeMaps.remove(uuid);
            GlobalQuakeLocal.instance.getLocalEventHandler().fireEvent(new ShakeMapsUpdatedEvent());
        });
    }

    private void updateShakemap(Earthquake earthquake) {
        shakemapService.submit(() -> {
            shakeMaps.put(earthquake.getUuid(), createShakemap(earthquake));
            GlobalQuakeLocal.instance.getLocalEventHandler().fireEvent(new ShakeMapsUpdatedEvent());
        });
    }

    private ShakeMap createShakemap(Earthquake earthquake) {
        Hypocenter hyp = earthquake.getCluster().getPreviousHypocenter();
        double mag = hyp.magnitude;
        return new ShakeMap(hyp, mag < 5.2 ? 6 : mag < 6.4 ? 5 : mag < 8.5 ? 4 : 3);
    }

    public void stop(){
        GlobalQuake.instance.stopService(shakemapService);
        GlobalQuake.instance.stopService(checkService);
    }

    public Map<UUID, ShakeMap> getShakeMaps() {
        return shakeMaps;
    }
}
