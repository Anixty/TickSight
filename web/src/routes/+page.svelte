<script lang="ts">
  import { goto } from '$app/navigation';
  import { localData, localReportFile, notification } from '$lib/stores';
  import logo from '../assets/logo.svg';

  let files: FileList | null = null;

  $: if (files && files?.length > 0) {
    const file = files[0];
    file.arrayBuffer().then((data) => {
      try {
        let string_data = new TextDecoder().decode(data);
        $localData = JSON.parse(string_data);
        $localReportFile = { bytes: file.size, source: 'file', fileName: file.name };
        goto('/p');
      } catch (e) {
        $notification = `Couldn't open ${file.name}: ${e}`;
      }
    });
  }
</script>

<div class="w-full flex flex-col items-center">
  <div class="bg-neutral-800 w-full flex flex-col items-center pt-4 pb-1">
    <div class="mt-1 flex items-center gap-3">
      <img src={logo} alt="" width="72" height="72" />
      <p class="text-6xl">TickSight</p>
    </div>
    <p>Profile viewer based on <a href="https://github.com/tasgon/observable_web">Observable by tas</a></p>
  </div>
  <div class="max-w-xl pt-1 gap-3 [&>*]:my-2">
    <h1>What is this?</h1>
    <p>
      TickSight profiles block entities and entities and shows you what's taking up tick time and where. It's available for Minecraft 1.20.1+
      on (Neo)Forge and Fabric. Get it on
      <a href="https://github.com/Anixty/TickSight">GitHub</a>.
    </p>

    <h1>How do I use it?</h1>
    <div>
      <p>
        Access to the profiler screen is restricted to operators by default on dedicated servers.
        Operators can grant access to the screen with either command:
      </p>
      <ul class="list-disc ml-6 mt-1">
        <li><code>/ticksight set allPlayersAllowed true</code> to allow everyone, or</li>
        <li><code>/ticksight allow &lt;username&gt;</code> to allow a specific user.</li>
      </ul>
    </div>
      
    <p>
      With the client mod installed, bind the "Show profiler screen" key to open the profiler.
      Level 4 operators can run server commands from any Minecraft client. Use
      <code>/ticksight help run</code> for profiling examples and <code>/ticksight configure</code> to edit server settings.
    </p>

    <h1>I'm still confused!</h1>
    <p>
      The original viewer is by <a href="https://github.com/tasgon/observable_web">tasgon</a>.
      Report TickSight issues on <a href="https://github.com/Anixty/TickSight/issues">GitHub</a>.
    </p>

    <form>
      <label for="file-upload" class="cursor-pointer rounded-md bg-slate-800 w-full py-2 flex justify-center">
        <p class="text-blue-400 text-lg">
          Have a local profile you want to analyze? Open it here.
        </p>
      </label>
      <input type="file" id="file-upload" style="display: none;" bind:files accept=".json" />
    </form>
  </div>
</div>

<style lang="postcss">
  a {
    @apply text-blue-400;
  }

  h1 {
    @apply text-2xl font-semibold;
  }
</style>
