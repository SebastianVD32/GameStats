// Maintainer/CI tooling only. No browser dependency is shipped in any image.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const base = process.argv[2] || 'http://127.0.0.1:8090';
  const output = process.argv[3] || '.cache/panel-browser-evidence';
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({headless:true, ...(process.env.PLAYWRIGHT_CHANNEL ? {channel:process.env.PLAYWRIGHT_CHANNEL} : {})});
  const page = await browser.newPage({viewport:{width:1360,height:960}});
  const errors = [], external = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', req => {if(new URL(req.url()).origin !== new URL(base).origin) external.push(req.url());});
  try {
    await page.goto(base);
    await page.locator('#health').getByText('Go: Disponible').waitFor();
    await page.locator('#health').getByText('Scala: Disponible').waitFor();
    await page.locator('#start:enabled').waitFor();
    await page.locator('#start').click();
    await page.locator('#start:disabled').waitFor();
    await page.locator('#notice').getByText('Demostración aprobada.', {exact:false}).waitFor({timeout:180000});
    const first = await page.locator('#runs').inputValue();
    const run = await (await page.request.get(base+'/api/runs/'+first)).json();
    assert.equal(run.status,'passed');
    assert.equal(run.matches.length,14);
    assert(run.checks.every(c=>c.passed));
    for (const [game,count,label] of [['CHESS',3,'Movimientos totales'],['BASKETBALL',3,'Equipos / marcador'],['RACING',4,'Clasificación final'],['PACMAN',4,'Puntuación total']]) {
      await page.selectOption('#game',game);
      assert.equal(await page.locator('#matches tbody tr').count(),count);
      await page.locator('#matches button').first().click();
      await page.locator('#result').getByText(label,{exact:true}).first().waitFor();
      assert((await page.locator('#patterns').innerText()).includes('Patrones y evidencia'));
      assert(!(await page.locator('#result').innerText()).includes('undefined'));
    }
    await page.selectOption('#game','BASKETBALL');
    await page.locator('#matches button').first().click();
    const freeThrows = page.locator('#result summary').filter({hasText:/^Tiros libres$/}).first();
    await freeThrows.click();
    await freeThrows.locator('..').getByText('Sin intentos',{exact:true}).waitFor();
    await page.selectOption('#check-filter','rejections');
    await page.locator('#checks').getByText('502',{exact:true}).first().waitFor();
    await page.selectOption('#check-filter','failed');
    await page.locator('#checks').getByText('No hay comprobaciones para este filtro.').waitFor();
    await page.reload();
    await page.locator('#notice').getByText('Demostración aprobada.',{exact:false}).waitFor();
    assert.equal(await page.locator('#runs').inputValue(),first);
    await page.locator('#start').click();
    await page.locator('#start:disabled').waitFor();
    await page.locator('#notice').getByText('Demostración aprobada.',{exact:false}).waitFor({timeout:180000});
    const second=await page.locator('#runs').inputValue();
    assert.notEqual(first,second);
    await page.selectOption('#runs',first);
    await page.selectOption('#game','CHESS');
    await page.locator('#matches button').first().click();
    await page.locator('#result').getByText('Movimientos totales',{exact:true}).waitFor();
    await page.screenshot({path:path.join(output,'panel.png'),fullPage:true});
    // Exercise UI failure handling without shutting down any real service.
    await page.route('**/api/results/**',route=>route.fulfill({status:502,contentType:'application/json',body:'{"error":"Servicio no disponible (prueba de interfaz)"}'}));
    await page.locator('#matches button').first().click();
    await page.locator('#result').getByText('Resultado no disponible:',{exact:false}).waitFor();
    assert.deepEqual(errors,[]);
    assert.deepEqual(external,[]);
    fs.writeFileSync(path.join(output,'browser.json'),JSON.stringify({passed:true,runs:[first,second],games:4,errors,external,simulatedError:true},null,2));
    console.log('PASS: browser start, demos, filters, all games, nulls, expected rejections, reload, repetition and simulated error');
  } catch (error) {
    await page.screenshot({path:path.join(output,'failure.png'),fullPage:true});
    throw error;
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1);});
