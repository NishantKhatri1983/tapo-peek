import unittest

from companion.detection import Detection
from companion.events import Events, inside, iou, valid_zone


class EventsTests(unittest.TestCase):
    def setUp(self):
        self.person = Detection('person', .9, (10, 10, 50, 90))
        self.other = Detection('person', .8, (60, 10, 95, 90))
        self.cat = Detection('cat', .85, (12, 60, 35, 90))

    def test_object_confirm_once_and_expires(self):
        events = Events('stairs')
        self.assertEqual(events.step([self.cat], 0), [])
        self.assertEqual(events.step([], .1), [])
        self.assertEqual(events.step([self.cat], .2), [])
        first, = events.step([self.cat], .3)
        self.assertEqual((first['label'], first['type']), ('cat', 'detected'))
        self.assertEqual(events.step([self.cat], .8), [])
        self.assertEqual(events.step([self.cat], 4), [])
        second, = events.step([self.cat], 4.1)
        self.assertNotEqual(first['event_id'], second['event_id'])

    def test_name_updates_same_event_after_three_agreeing_samples(self):
        events = Events('stairs')
        def match(d):
            return ('Example', .8)
        self.assertFalse(events.step([self.person], 0, match))
        first, = events.step([self.person], .25, match)
        self.assertNotIn('name', first)
        named, = events.step([self.person], .5, match)
        self.assertEqual(named['name'], 'Example')
        self.assertEqual(named['event_id'], first['event_id'])
        self.assertEqual(events.step([self.person], .75, match), [])

    def test_multiple_people_and_inconsistent_faces_do_not_confirm(self):
        events = Events('stairs', confirm=1)
        def no_match_attempt(d):
            self.fail('Matching should not run for simultaneous people.')
        self.assertEqual(len(events.step([self.person, self.other], 0, no_match_attempt)), 2)
        for time, name in [(.25, 'One'), (.5, 'Two'), (.75, 'One'), (1, None), (1.25, 'One'), (1.5, 'One')]:
            self.assertFalse(events.step([self.person], time, lambda d: (name, .8) if name else None))

    def test_zone_includes_bottom_center(self):
        self.assertTrue(valid_zone((.15, .8, .4, 1)))
        self.assertTrue(inside(self.person.box, (.15, .8, .4, 1), 100, 100))
        self.assertFalse(inside(self.person.box, (.5, .8, .9, 1), 100, 100))
        self.assertFalse(valid_zone((0, 0, 0, 1)))
        self.assertFalse(valid_zone((0, 0, 1, float('nan'))))
        self.assertEqual(iou(self.person.box, self.person.box), 1)
        self.assertEqual(iou(self.person.box, self.other.box), 0)


if __name__ == '__main__':
    unittest.main()
